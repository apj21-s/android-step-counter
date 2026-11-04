package com.arco.stride;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shown by Android when you ask why Stride wants Health Connect access. */
public class PrivacyActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        t.setTextColor(0xEBFFFFFF);
        t.setTextSize(16);
        t.setLineSpacing(0, 1.3f);
        t.setText(getString(R.string.privacy_text));
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(0xFF121212);
        s.addView(t);
        setContentView(s);
    }
}
