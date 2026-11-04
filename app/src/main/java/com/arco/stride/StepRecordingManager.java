package com.arco.stride;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.fitness.FitnessLocal;
import com.google.android.gms.fitness.FitnessStatusCodes;
import com.google.android.gms.fitness.LocalRecordingClient;
import com.google.android.gms.fitness.data.LocalBucket;
import com.google.android.gms.fitness.data.LocalDataPoint;
import com.google.android.gms.fitness.data.LocalDataSet;
import com.google.android.gms.fitness.data.LocalDataType;
import com.google.android.gms.fitness.data.LocalField;
import com.google.android.gms.fitness.request.LocalDataReadRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Google's Recording API on mobile: Play services counts steps in the background at very low
 * power, even when Stride isn't running, and keeps 10 days of data. We subscribe once (never
 * unsubscribe: that deletes the data) and read it back in 15-minute buckets.
 */
final class StepRecordingManager {
    interface Done { void done(); }

    private StepRecordingManager() {}

    /**
     * Play services new enough for step recording. play-services-fitness 21.2.0 has only this constant
     * (241500000); the steps-only LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE (same value) arrives in 21.3.0.
     */
    static boolean available(Context c) {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c,
                LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE) == ConnectionResult.SUCCESS;
    }

    /** True when the last subscribe worked, so Play services is doing the counting. */
    static boolean active(Context c) { return StepStore.prefs(c).getBoolean("recording", false); }

    /** Subscribe (safe to repeat: an existing subscription makes this a no-op). */
    static void subscribe(Context c, Done done) {
        Context app = c.getApplicationContext();
        SharedPreferences p = StepStore.prefs(app);
        if (app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED || !available(app)) {
            p.edit().putBoolean("recording", false).apply();
            done.done();
            return;
        }
        FitnessLocal.getLocalRecordingClient(app).subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
                .addOnSuccessListener(v -> {
                    SharedPreferences.Editor e = p.edit().putBoolean("recording", true);
                    if (!p.contains("recordingSince")) e.putLong("recordingSince", System.currentTimeMillis());
                    e.apply();
                    done.done();
                })
                .addOnFailureListener(e -> {
                    p.edit().putBoolean("recording", false).apply();
                    done.done();
                });
    }

    /** Read the last 7 days and replace our "recording" buckets with what Play services has. */
    static void sync(Context c, Done done) {
        Context app = c.getApplicationContext();
        SharedPreferences p = StepStore.prefs(app);
        if (!active(app) || app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            done.done();
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        // Only replace data recorded under the current subscription, so a lost subscription can't wipe history.
        long from = Math.max(StepRepository.midnight(LocalDate.now(zone).minusDays(6), zone),
                StepRepository.bucketStart(p.getLong("recordingSince", now)));
        LocalDataReadRequest request = new LocalDataReadRequest.Builder()
                .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
                .bucketByTime(15, TimeUnit.MINUTES)
                .setTimeRange(from, now, TimeUnit.MILLISECONDS)
                .build();
        FitnessLocal.getLocalRecordingClient(app).readData(request)
                .addOnSuccessListener(response -> {
                    if (!response.getStatus().isSuccess()) {
                        done.done();
                        return;
                    }
                    List<LocalBucket> buckets = response.getBuckets();
                    long[] starts = new long[buckets.size()];
                    long[] steps = new long[buckets.size()];
                    for (int i = 0; i < buckets.size(); i++) {
                        LocalBucket b = buckets.get(i);
                        starts[i] = b.getStartTime(TimeUnit.MILLISECONDS);
                        for (LocalDataSet set : b.getDataSets()) {
                            for (LocalDataPoint point : set.getDataPoints()) {
                                steps[i] += point.getValue(LocalField.FIELD_STEPS).asInt();
                            }
                        }
                    }
                    StepRepository.get(app).replaceRange("recording", from, now + 1, starts, steps, zone, now);
                    p.edit().putLong("lastSync", now).apply();
                    Widgets.updateAll(app);
                    HealthShare.write(app);
                    done.done();
                })
                .addOnFailureListener(e -> {
                    // No subscription any more (e.g. Play services data was cleared): subscribe again, fresh start.
                    if (e instanceof ApiException && ((ApiException) e).getStatusCode() == FitnessStatusCodes.API_EXCEPTION) {
                        p.edit().remove("recordingSince").apply();
                        subscribe(app, done);
                        return;
                    }
                    done.done();
                });
    }
}
