package com.arco.stride;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Background sync without keeping anything running:
 *  - every ~1 hour: read the Recording API and redraw widgets,
 *  - just after midnight: so widgets switch to the new day,
 *  - both times: restart the step service if something killed it.
 */
public class StepSyncJob extends JobService {
    static final int HOURLY = 1, MIDNIGHT = 2;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(HOURLY) == null) {
            js.schedule(new JobInfo.Builder(HOURLY, new ComponentName(c, StepSyncJob.class))
                    .setPeriodic(60 * 60_000L, 15 * 60_000L)
                    .setPersisted(true)
                    .build());
        }
        scheduleMidnight(c);
    }

    static void scheduleMidnight(Context c) {
        ZoneId zone = ZoneId.systemDefault();
        long wait = StepRepository.midnight(LocalDate.now(zone).plusDays(1), zone) + 60_000L - System.currentTimeMillis();
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(MIDNIGHT, new ComponentName(c, StepSyncJob.class))
                .setMinimumLatency(Math.max(0, wait))
                .setOverrideDeadline(Math.max(0, wait) + 10 * 60_000L)
                .setPersisted(true)
                .build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (params.getJobId() == MIDNIGHT) scheduleMidnight(this);
        // watchdog: phone makers' battery savers (e.g. Motorola Sleep mode) kill the service and nothing
        // restarts it. Android only allows this restart when Stride is exempt from battery optimisation.
        StepService.start(this);
        StepRecordingManager.sync(this, () -> {
            Widgets.updateAll(this);
            StepService.refresh(this, true);
            jobFinished(params, false);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
