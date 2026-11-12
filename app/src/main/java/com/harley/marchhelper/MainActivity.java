package com.harley.marchhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int C_BG = Color.BLACK;
    private static final int C_CARD = Color.parseColor("#1C1C1E");
    private static final int C_TINT = Color.parseColor("#F2B544");
    private static final int C_TEXT = Color.WHITE;
    private static final int C_DIM = Color.parseColor("#99EBEBF5");
    private static final int C_LINE = Color.parseColor("#A6545458");

    private Button permBtn;
    private Button startBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(24));

        // large title with app badge
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView badge = new ImageView(this);
        badge.setImageResource(R.drawable.badge_march);
        head.addView(badge, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(12), 0, 0, 0);
        TextView title = text("Overlay", 34, C_TEXT);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        TextView sub = text("Marchitect · floating PvP helper", 15, C_DIM);
        tt.addView(title);
        tt.addView(sub);
        head.addView(tt);
        root.addView(head);

        root.addView(section("How it works"));
        LinearLayout card = card();
        card.addView(row("1", "Allow Marchitect to display over other apps."));
        card.addView(divider());
        card.addView(row("2", "Tap Start, then allow screen capture so the overlay can read Evony. The gold bubble appears."));
        card.addView(divider());
        card.addView(row("3", "In Evony, tap the bubble and pick Layers or Scout. Each opens a small panel under the bubble; › expands it, — tucks it away."));
        card.addView(divider());
        card.addView(row("4", "Open Create Presets and your march size fills in. Open a scout report and scroll the troop list: Scout shows their top-tier mix and what to send."));
        root.addView(card);

        root.addView(section("Setup"));
        permBtn = button("Allow display over other apps", false);
        permBtn.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(permBtn);

        startBtn = button("Start overlay", true);
        startBtn.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) return;
            startForegroundService(new Intent(this, OverlayService.class));
            moveTaskToBack(true);
        });
        root.addView(startBtn);

        Button stopBtn = button("Stop overlay", false);
        stopBtn.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        root.addView(stopBtn);

        TextView note = text(
                "Auto-read uses Android's screen capture to read march sizes and scout reports. Text is read on your phone "
                        + "and never leaves it. Android asks for permission each time the overlay starts.\n\n"
                        + "Marchitect is a fan-made tool and isn't affiliated with Evony or Top Games.",
                13, C_DIM);
        note.setPadding(dp(16), dp(14), dp(16), 0);
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.addView(root);
        setContentView(sv);

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
        permBtn.setText(ok ? "✓  Display over other apps allowed" : "Allow display over other apps");
        permBtn.setEnabled(!ok);
        permBtn.setAlpha(ok ? 0.55f : 1f);
        startBtn.setEnabled(ok);
        startBtn.setAlpha(ok ? 1f : 0.4f);
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 13, C_DIM);
        t.setPadding(dp(16), dp(26), 0, dp(7));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(10));
        c.setBackground(bg);
        return c;
    }

    private View row(String n, String body) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(16), dp(11), dp(16), dp(11));
        TextView num = text(n, 15, C_TINT);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        r.addView(num, new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(text(body, 15, C_TEXT), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.leftMargin = dp(54);
        d.setLayoutParams(lp);
        return d;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? Color.BLACK : C_TINT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? C_TINT : Color.parseColor("#2E767680"));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
