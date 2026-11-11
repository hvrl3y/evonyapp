package com.harley.marchhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private Button permBtn;
    private Button startBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12151C"));
        int pad = dp(24);
        root.setPadding(pad, dp(48), pad, pad);

        TextView title = text("March Helper", 26, "#E3B04B");
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView body = text(
                "A floating troop-counter and layering calculator you can open on top of Evony.\n\n"
                        + "1. Allow \"Display over other apps\".\n"
                        + "2. Tap Start. A gold bubble appears.\n"
                        + "3. Open Evony. Tap the bubble to open the panel, drag it to move it, "
                        + "and drag the bubble to the bottom center to close it.\n\n"
                        + "This app only shows information. It never reads or taps the game.",
                15, "#C9CED8");
        body.setPadding(0, dp(12), 0, dp(24));
        root.addView(body);

        permBtn = button("Allow display over other apps");
        permBtn.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(permBtn);

        startBtn = button("Start overlay");
        startBtn.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) return;
            Intent i = new Intent(this, OverlayService.class);
            startForegroundService(i);
            moveTaskToBack(true);
        });
        root.addView(startBtn);

        Button stopBtn = button("Stop overlay");
        stopBtn.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        root.addView(stopBtn);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        permBtn.setText(ok ? "✓ Display over other apps allowed" : "Allow display over other apps");
        permBtn.setEnabled(!ok);
        permBtn.setAlpha(ok ? 0.6f : 1f);
        startBtn.setEnabled(ok);
        startBtn.setAlpha(ok ? 1f : 0.4f);
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#E8EBF0"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1B202A"));
        bg.setStroke(dp(1), Color.parseColor("#E3B04B"));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
