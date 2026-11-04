package com.arco.stride;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.os.Build;
import android.os.OutcomeReceiver;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional: write Stride's steps to Health Connect (built into Android 14+) so other apps can
 * see them. One record per finished 15-minute bucket, with a fixed record ID per bucket, so
 * writing it again replaces it instead of adding a duplicate. We never read our data back.
 */
final class HealthShare {
    static final String PERMISSION = "android.permission.health.WRITE_STEPS";

    private HealthShare() {}

    static boolean supported() { return Build.VERSION.SDK_INT >= 34; }

    static boolean granted(Context c) {
        return supported() && c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean enabled(Context c) { return StepStore.prefs(c).getBoolean("share", false); }

    static void write(Context c) {
        if (Build.VERSION.SDK_INT < 34 || !enabled(c) || !granted(c)) return;
        HealthConnectManager hc = c.getSystemService(HealthConnectManager.class);
        if (hc == null) return;
        StepRepository repo = StepRepository.get(c);
        ZoneId zone = ZoneId.systemDefault();
        long from = StepRepository.midnight(LocalDate.now(zone).minusDays(6), zone);
        long current = StepRepository.bucketStart(System.currentTimeMillis());
        List<long[]> changed = repo.changedForHealth(from);
        changed.removeIf(b -> b[0] >= current || b[1] <= 0);    // only finished buckets with steps
        if (changed.isEmpty()) return;

        long version = System.currentTimeMillis();               // higher version replaces the older record
        List<Record> records = new ArrayList<>();
        for (long[] b : changed) {
            Instant start = Instant.ofEpochMilli(b[0]);
            Instant end = Instant.ofEpochMilli(b[0] + StepRepository.BUCKET_MS);
            Metadata md = new Metadata.Builder()
                    .setClientRecordId("stride-" + b[0])
                    .setClientRecordVersion(version)
                    .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
                    .build();
            records.add(new StepsRecord.Builder(md, start, end, b[1])
                    .setStartZoneOffset(zone.getRules().getOffset(start))
                    .setEndZoneOffset(zone.getRules().getOffset(end))
                    .build());
        }
        hc.insertRecords(records, c.getMainExecutor(), new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
            @Override public void onResult(InsertRecordsResponse r) { repo.markWritten(changed); }
            @Override public void onError(HealthConnectException e) { /* not marked: retried on the next sync */ }
        });
    }
}
