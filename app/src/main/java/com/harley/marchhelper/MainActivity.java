package com.harley.marchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Marchitect, full screen. The overlay (bubble with Layers / Scout / Muster) is started from the
 * gold "Start overlay" button inside the app. Both share the same saved data.
 */
public class MainActivity extends Activity {

    private WebView web;
    private boolean pendingStart = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Native(), "MarchitectNative");
        web.loadUrl("file:///android_asset/app.html");
        setContentView(web);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            startOverlayNow();
        }
        if (web != null) web.evaluateJavascript("window.marchitectOverlayChanged&&marchitectOverlayChanged()", null);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void startOverlayNow() {
        startForegroundService(new Intent(this, OverlayService.class));
        Toast.makeText(this, "Overlay on. Allow screen capture so it can read Evony.", Toast.LENGTH_LONG).show();
        web.postDelayed(() -> moveTaskToBack(true), 900);
    }

    @SuppressWarnings("deprecation")
    private boolean running() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE))
            if (OverlayService.class.getName().equals(s.service.getClassName())) return true;
        return false;
    }

    private class Native {
        @JavascriptInterface
        public void startOverlay() {
            runOnUiThread(() -> {
                if (Settings.canDrawOverlays(MainActivity.this)) {
                    startOverlayNow();
                } else {
                    pendingStart = true;
                    Toast.makeText(MainActivity.this, "Turn on \"Allow display over other apps\" for Marchitect, then come back.", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                }
            });
        }

        @JavascriptInterface
        public void stopOverlay() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, OverlayService.class)));
        }

        @JavascriptInterface
        public boolean overlayRunning() {
            return running();
        }
    }
}
