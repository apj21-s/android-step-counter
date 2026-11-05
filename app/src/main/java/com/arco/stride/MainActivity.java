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
                Insets i = in.getInsets(WindowInsets.Type.systemBars());
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

        @JavascriptInterface public void calStart() { runOnUiThread(StepEngine::startCalibration); }

        /** End a calibration walk of 100 steps. Returns what the motion detector counted (0 = failed). */
        @JavascriptInterface public int calStop(String place) {
            float[] r = StepEngine.stopCalibration();
            int steps = Math.round(r[0]);
            if (steps < 50) return steps;                                     // too far off to trust
            SharedPreferences p = StepStore.prefs(MainActivity.this);
            float gain = Math.max(0.8f, Math.min(1.25f, 100f / steps));
            float floor = Math.max(0.08f, Math.min(0.2f, r[1] * 0.4f));       // 40% of a typical step peak
            SharedPreferences.Editor e = p.edit().putFloat("gain_" + place, gain).putFloat("floor_" + place, floor);
            // use the average gain and the lower floor of the walks done so far
            float gHand = place.equals("hand") ? gain : p.getFloat("gain_hand", -1);
            float gPocket = place.equals("pocket") ? gain : p.getFloat("gain_pocket", -1);
            float fHand = place.equals("hand") ? floor : p.getFloat("floor_hand", 1);
            float fPocket = place.equals("pocket") ? floor : p.getFloat("floor_pocket", 1);
            float g = gHand > 0 && gPocket > 0 ? (gHand + gPocket) / 2 : Math.max(gHand, gPocket);
            e.putFloat("calGain", g).putFloat("calFloor", Math.min(fHand, fPocket)).apply();
            runOnUiThread(() -> {                                              // restart so the new values apply
                StepService.stop(MainActivity.this);
                StepService.start(MainActivity.this);
            });
            return steps;
        }

        /** Lab mode: record raw sensors for a hand-counted walk. place: hand, typing, pocket_front, pocket_back, bag, swing. */
        @JavascriptInterface public void labStart(String place) { LabRecorder.start(MainActivity.this, place); }

        /** Stop, save to Download/Stride lab, and return {truth, chip, detector, peaks, model, file} as JSON. */
        @JavascriptInterface public String labStop(int truth) { return LabRecorder.stop(truth); }

        /** Forget what Stride learned from your walks (and the calibration walks). */
        @JavascriptInterface public void learnReset() {
            calReset();
            StepEngine.resetLearning(MainActivity.this);
            runOnUiThread(() -> {                                              // restart so the defaults apply
                StepService.stop(MainActivity.this);
                StepService.start(MainActivity.this);
            });
        }

        @JavascriptInterface public void calReset() {
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
