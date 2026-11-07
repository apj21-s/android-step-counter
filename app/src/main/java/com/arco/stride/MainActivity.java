package com.arco.stride;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.Locale;

/** Shows the app screens (the approved web design) and connects them to the step data. */
public class MainActivity extends Activity {
    private static final int ASK_ACTIVITY = 1, ASK_HEALTH = 2;
    private WebView web;
    private FrameLayout root;

    @SuppressLint("SetJavaScriptEnabled")   // the WebView only ever loads Stride's own bundled page
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LabRecorder.recoverLeftover(this);          // a recording cut off by a kill: save what there is
        root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        applyBackground();
        root.addView(web);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) {           // Android 15+ draws edge to edge: keep content off the bars
            root.setOnApplyWindowInsetsListener((v, in) -> {
                // the keyboard counts too, so a focused text box is never hidden behind it
                Insets i = Insets.max(in.getInsets(WindowInsets.Type.systemBars()), in.getInsets(WindowInsets.Type.ime()));
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        StepRepository.get(this).migrateLegacy(StepStore.prefs(this));
        StepSyncJob.schedule(this);
        web.loadUrl("file:///android_asset/www/index.html");
        if (!StepService.hasPermission(this)) askPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        StepEngine.startLive(this, n -> web.evaluateJavascript("window.onSteps&&onSteps(" + n + ")", null));
        startCounting();
    }

    @Override protected void onPause() {
        StepEngine.stopLive();
        Widgets.updateAll(this);
        web.onPause();
        web.pauseTimers();                    // no JavaScript timers while the app isn't visible
        super.onPause();
    }

    /** Subscribe (no-op if already), sync, then start the background service only if it's needed. */
    private void startCounting() {
        if (!StepService.hasPermission(this)) return;
        StepRecordingManager.subscribe(this, () -> {
            StepService.start(this);
            StepRecordingManager.sync(this, this::pushState);
        });
    }

    private void pushState() {
        web.evaluateJavascript("window.onState&&onState(" + StepStore.stateJson(this) + ")", null);
    }

    private void askPermission() {
        SharedPreferences p = StepStore.prefs(this);
        boolean asked = p.getBoolean("askedPerm", false);
        if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // Denied with "don't ask again": the only way left is the app's settings page.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        p.edit().putBoolean("askedPerm", true).apply();
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.ACTIVITY_RECOGNITION};
        requestPermissions(perms, ASK_ACTIVITY);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == ASK_ACTIVITY && StepService.hasPermission(this)) {
            StepEngine.startLive(this, n -> web.evaluateJavascript("window.onSteps&&onSteps(" + n + ")", null));
            startCounting();
        }
        if (code == ASK_HEALTH) {
            StepStore.prefs(this).edit().putBoolean("share", HealthShare.granted(this)).apply();
            HealthShare.write(this);
        }
        pushState();
    }

    private void applyBackground() {
        int bg = StepStore.prefs(this).getBoolean("amoled", false) ? Color.BLACK : 0xFF121212;
        root.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
    }

    /** Called from the page's JavaScript as window.Android.* */
    private class Bridge {
        private SharedPreferences.Editor edit() { return StepStore.prefs(MainActivity.this).edit(); }

        private void changed() {
            Widgets.updateAll(MainActivity.this);
            StepService.refresh(MainActivity.this, true);
        }

        @JavascriptInterface public String state() { return StepStore.stateJson(MainActivity.this); }
        @JavascriptInterface public void setGoal(int g) { edit().putInt("goal", Math.max(500, Math.min(100000, g))).apply(); changed(); }
        @JavascriptInterface public void setHeight(int h) { edit().putInt("height", Math.max(100, Math.min(250, h))).apply(); changed(); }
        @JavascriptInterface public void setHaptics(boolean on) { edit().putBoolean("haptics", on).apply(); }
        @JavascriptInterface public void setWidgetStyle(String s) { edit().putString("wstyle", s).apply(); changed(); }

        @JavascriptInterface public void setAccent(String hex, String a2, String glow) {
            try {
                edit().putString("accent", hex).putInt("a1", Color.parseColor(hex)).putInt("a2", Color.parseColor(a2)).apply();
                changed();
            } catch (IllegalArgumentException e) {
                // not a colour: keep the old one
            }
        }

        @JavascriptInterface public void setAmoled(boolean on) {
            edit().putBoolean("amoled", on).apply();
            runOnUiThread(MainActivity.this::applyBackground);
        }

        /** Max accuracy changes whether the background service is needed. */
        @JavascriptInterface public void setBoost(boolean on) {
            edit().putBoolean("boost", on).apply();
            runOnUiThread(() -> {
                StepService.stop(MainActivity.this);
                StepService.start(MainActivity.this);
            });
        }

        @JavascriptInterface public void setShare(boolean on) {
            edit().putBoolean("share", on).apply();
            if (!on || !HealthShare.supported()) return;
            if (HealthShare.granted(MainActivity.this)) HealthShare.write(MainActivity.this);
            else runOnUiThread(() -> requestPermissions(new String[]{HealthShare.PERMISSION}, ASK_HEALTH));
        }

        /**
         * Record a walk you count by hand (calibration and Lab recordings are the same thing).
         * place: hand, typing, swing, pocket_front, pocket_back, bag, car. Returns "" or an error message.
         */
        @JavascriptInterface public String recStart(String place) {
            String err = LabRecorder.start(MainActivity.this, place);
            return err == null ? "" : err;
        }

        @JavascriptInterface public String recStatus() { return LabRecorder.status(); }

        @JavascriptInterface public void recCancel() { LabRecorder.cancel(MainActivity.this); }

        @JavascriptInterface public String recList() { return LabRecorder.list(MainActivity.this); }

        /**
         * Stop and save. With calibrate=true and a steady-hand walk of 50+ steps, the motion detector's
         * gain and smallest step are set from it (motion only counts for a steady hand; elsewhere the
         * chip is used, so other positions just report how the chip did).
         */
        @JavascriptInterface public String recStop(int truth, boolean calibrate) {
            String json = LabRecorder.stop(MainActivity.this, truth);
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                boolean hand = "hand".equals(o.optString("place")) || "typing".equals(o.optString("place"));
                long est = o.optLong("hand");
                if (calibrate && hand && truth >= 50 && est > 0 && !o.has("error")) {
                    float gain = Math.max(0.8f, Math.min(1.25f, truth / (float) est));
                    float floor = Math.max(0.05f, Math.min(0.15f, 0.4f * (float) o.optDouble("medianPeak", 0.3)));
                    edit().putFloat("calGain", gain).putFloat("calFloor", floor).apply();
                    o.put("calibrated", true).put("gain", gain);
                    runOnUiThread(() -> {                                      // restart so the new values apply
                        StepService.stop(MainActivity.this);
                        StepService.start(MainActivity.this);
                    });
                }
                return o.toString();
            } catch (org.json.JSONException e) {
                return json;
            }
        }

        /** Forget what Stride learned from your walks (and the calibration walks). */
        @JavascriptInterface public void learnReset() {
            calReset();
            StepEngine.resetLearning(MainActivity.this);
            runOnUiThread(() -> {                                              // restart so the defaults apply
                StepService.stop(MainActivity.this);
                StepService.start(MainActivity.this);
            });
        }

        private void calReset() {
            edit().remove("calGain").remove("calFloor").remove("gain_hand").remove("gain_pocket")
                    .remove("floor_hand").remove("floor_pocket").apply();
        }

        @JavascriptInterface public void vibrate(int ms) {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
        }

        @JavascriptInterface public void openBatteryGuide() {
            String brand = Build.MANUFACTURER.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/" + brand))));
        }

        @JavascriptInterface public void requestPermission() { runOnUiThread(MainActivity.this::askPermission); }

        @JavascriptInterface public boolean batteryOk() {
            return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        }

        /** Only when the user taps the button: opens Android's battery-optimisation list, where they can pick Stride. */
        @JavascriptInterface public void batteryExempt() {
            if (batteryOk()) return;
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        }
    }
}
