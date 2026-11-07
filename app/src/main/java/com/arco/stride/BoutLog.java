package com.arco.stride;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One line per walking bout in Download/Stride lab/bouts.txt, so a day's extra or missing steps can
 * be traced to the bouts that caused them (tools/phone/sync.sh pulls it). Only when "Save walk log"
 * is on in Settings (off by default), and never more than MAX_BYTES: it starts over when full.
 * Columns: end time, seconds, position, chip, motion estimate, motion stored (after the cap),
 * cap %, vehicle steps removed, calibration gain.
 */
final class BoutLog {
    private static final String NAME = "bouts.txt", DIR = "Download/Stride lab/";
    private static final String HEADER = "# end,seconds,position,chip,motion,stored,cap%,vehicle_removed,gain\n";
    private static final long MAX_BYTES = 256 * 1024;      // ~4,000 walks, several months
    private static Uri uri;

    private BoutLog() {}

    static synchronized void append(Context c, String line) {
        if (!StepStore.prefs(c).getBoolean("walkLog", false)) return;
        try {
            Uri u = find(c);
            boolean full = false;
            try (Cursor cur = c.getContentResolver().query(u, new String[]{MediaStore.Downloads.SIZE}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) full = cur.getLong(0) > MAX_BYTES;
            }
            try (OutputStream o = c.getContentResolver().openOutputStream(u, full ? "wt" : "wa")) {
                if (o != null && full) o.write(HEADER.getBytes(StandardCharsets.UTF_8));
                if (o != null) o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            uri = null;              // file deleted by the user etc.: make a new one next time
        }
    }

    private static Uri find(Context c) {
        if (uri != null) return uri;
        try (Cursor cur = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + " = ? AND " + MediaStore.Downloads.RELATIVE_PATH + " = ?", new String[]{NAME, DIR}, null)) {
            if (cur != null && cur.moveToFirst()) {
                uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(cur.getLong(0)));
                return uri;
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, NAME);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, DIR);
        uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("can't create " + NAME);
        try (OutputStream o = c.getContentResolver().openOutputStream(uri, "wa")) {
            if (o != null) o.write(HEADER.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
        return uri;
    }
}
