package com.arco.stride;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.Sensor;
import android.hardware.SensorManager;
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

/** Shows the app screens (the approved web design) and connects them to the step sensor. */
public class MainActivity extends Activity {
    private WebView web;
    private FrameLayout root;
    private Sensor sensor;

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
        web.loadUrl("file:///android_asset/www/index.html");

        sensor = getSystemService(SensorManager.class).getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        RefreshReceiver.schedule(this);
        if (!StepService.hasPermission(this)) askPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        StepEngine.acquire(this);
        StepEngine.listener = n -> web.evaluateJavascript("window.onSteps&&onSteps(" + n + ")", null);
        StepService.start(this);
    }

    @Override protected void onPause() {
        StepEngine.listener = null;
        StepEngine.release(this);
        super.onPause();
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
        requestPermissions(perms, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (!StepService.hasPermission(this)) return;
        StepEngine.ensure(this);
        StepService.start(this);
        // Ask once to be left alone by battery saving, so counting never pauses.
        SharedPreferences p = StepStore.prefs(this);
        if (!p.getBoolean("askedBattery", false) && !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            p.edit().putBoolean("askedBattery", true).apply();
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        }
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
        private void changed() { Widgets.updateAll(MainActivity.this); StepService.refresh(); }

        @JavascriptInterface public String state() {
            return StepStore.stateJson(MainActivity.this, sensor != null, StepService.hasPermission(MainActivity.this), StepEngine.debug());
        }
        @JavascriptInterface public void setGoal(int g) { edit().putInt("goal", Math.max(500, Math.min(100000, g))).apply(); changed(); }
        @JavascriptInterface public void setHeight(int h) { edit().putInt("height", Math.max(100, Math.min(250, h))).apply(); changed(); }
        @JavascriptInterface public void setHaptics(boolean on) { edit().putBoolean("haptics", on).apply(); }
        @JavascriptInterface public void setBoost(boolean on) {
            edit().putBoolean("boost", on).apply();
            runOnUiThread(() -> StepEngine.reconfigure(MainActivity.this));
        }
        @JavascriptInterface public void setWidgetStyle(String s) { edit().putString("wstyle", s).apply(); changed(); }
        @JavascriptInterface public void setAccent(String hex, String a2, String glow) {
            try {
                edit().putString("accent", hex).putInt("a1", Color.parseColor(hex)).putInt("a2", Color.parseColor(a2)).apply();
                changed();
            } catch (IllegalArgumentException ignored) { }
        }
        @JavascriptInterface public void setAmoled(boolean on) {
            edit().putBoolean("amoled", on).apply();
            runOnUiThread(MainActivity.this::applyBackground);
        }
        @JavascriptInterface public void setLive(boolean on) {
            edit().putBoolean("live", on).apply();
            runOnUiThread(() -> {
                if (on) StepService.start(MainActivity.this); else StepService.stop(MainActivity.this);
                Widgets.updateAll(MainActivity.this);
            });
        }
        @JavascriptInterface public void vibrate(int ms) {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
        }
        @JavascriptInterface public void openBatteryGuide() {
            String brand = Build.MANUFACTURER.toLowerCase(java.util.Locale.US).replaceAll("[^a-z]", "");
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/" + brand))));
        }
        @JavascriptInterface public void requestPermission() { runOnUiThread(MainActivity.this::askPermission); }
        @JavascriptInterface public boolean batteryOk() {
            return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        }
        @JavascriptInterface public void batteryExempt() {
            if (batteryOk()) return;
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))));
        }
    }
}
