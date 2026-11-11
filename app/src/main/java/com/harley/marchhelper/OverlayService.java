package com.harley.marchhelper;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayService extends Service {

    private static final String CHANNEL = "overlay";
    private static final String ACTION_STOP = "com.harley.marchhelper.STOP";

    private WindowManager wm;
    private Handler main;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private WebView web;
    private boolean panelShown = false;
    private final float[] alphas = {1f, 0.85f, 0.65f};
    private int alphaIdx = 0;

    private TextView trash;
    private WindowManager.LayoutParams trashLp;
    private boolean trashShown = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        goForeground();
        createBubble();
        createPanel();
        createTrash();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        safeRemove(bubble);
        if (panelShown) safeRemove(panel);
        if (trashShown) safeRemove(trash);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------- foreground notification ----------

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW));

        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("March Helper is running")
                .setContentText("Tap the gold bubble to open the panel.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", stop).build())
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- bubble ----------

    @SuppressLint("ClickableViewAccessibility")
    private void createBubble() {
        bubble = new TextView(this);
        bubble.setText("⚔");
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bubble.setTextColor(Color.parseColor("#1B1B1B"));
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E3B04B"));
        bg.setStroke(dp(2), Color.parseColor("#1B202A"));
        bubble.setBackground(bg);
        bubble.setElevation(dp(6));

        bubbleLp = overlayParams(dp(52), dp(52), false);
        bubbleLp.x = 0;
        bubbleLp.y = screen().y / 3;

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                            showTrash(true);
                        }
                        if (dragging) {
                            bubbleLp.x = startX + (int) dx;
                            bubbleLp.y = startY + (int) dy;
                            wm.updateViewLayout(bubble, bubbleLp);
                            highlightTrash(overTrash());
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) {
                            togglePanel();
                        } else {
                            boolean kill = overTrash();
                            showTrash(false);
                            if (kill) {
                                stopSelf();
                            } else {
                                snapToEdge();
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
    }

    private void snapToEdge() {
        Point s = screen();
        int size = bubble.getWidth();
        int target = (bubbleLp.x + size / 2 < s.x / 2) ? 0 : s.x - size;
        bubbleLp.y = Math.max(0, Math.min(bubbleLp.y, s.y - size - dp(24)));
        ValueAnimator a = ValueAnimator.ofInt(bubbleLp.x, target);
        a.setDuration(180);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            bubbleLp.x = (int) an.getAnimatedValue();
            try {
                wm.updateViewLayout(bubble, bubbleLp);
            } catch (Exception ignored) {
            }
        });
        a.start();
    }

    // ---------- dismiss target ----------

    private void createTrash() {
        trash = new TextView(this);
        trash.setText("✕");
        trash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        trash.setTextColor(Color.WHITE);
        trash.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC2A313E"));
        trash.setBackground(bg);
        trashLp = overlayParams(dp(64), dp(64), false);
        trashLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        trashLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashLp.x = 0;
        trashLp.y = dp(48);
    }

    private void showTrash(boolean show) {
        if (show && !trashShown) {
            wm.addView(trash, trashLp);
            trashShown = true;
        } else if (!show && trashShown) {
            safeRemove(trash);
            trashShown = false;
        }
    }

    private void highlightTrash(boolean on) {
        GradientDrawable bg = (GradientDrawable) trash.getBackground();
        bg.setColor(Color.parseColor(on ? "#E2675B" : "#CC2A313E"));
        trash.setScaleX(on ? 1.2f : 1f);
        trash.setScaleY(on ? 1.2f : 1f);
    }

    private boolean overTrash() {
        Point s = screen();
        int bx = bubbleLp.x + dp(26), by = bubbleLp.y + dp(26);
        int tx = s.x / 2, ty = s.y - dp(48) - dp(32);
        return Math.hypot(bx - tx, by - ty) < dp(80);
    }

    // ---------- panel ----------

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void createPanel() {
        panel = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    hidePanel();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#12151C"));
        bg.setStroke(dp(1), Color.parseColor("#E3B04B"));
        bg.setCornerRadius(dp(12));
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setElevation(dp(8));

        // header: drag handle + buttons
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.parseColor("#1B202A"));
        header.setPadding(dp(12), 0, dp(4), 0);

        TextView title = new TextView(this);
        title.setText("⠿  March Helper");
        title.setTextColor(Color.parseColor("#E3B04B"));
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);

        TextView fade = headerBtn("◐");
        fade.setOnClickListener(v -> {
            alphaIdx = (alphaIdx + 1) % alphas.length;
            panelLp.alpha = alphas[alphaIdx];
            wm.updateViewLayout(panel, panelLp);
        });
        header.addView(fade);

        TextView close = headerBtn("—");
        close.setOnClickListener(v -> hidePanel());
        header.addView(close);

        header.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float dx, dy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = panelLp.x;
                        sy = panelLp.y;
                        dx = e.getRawX();
                        dy = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = sx + (int) (e.getRawX() - dx);
                        panelLp.y = Math.max(0, sy + (int) (e.getRawY() - dy));
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                }
                return false;
            }
        });
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#12151C"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Overlay");
        web.loadUrl("file:///android_asset/index.html");
        panel.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Point s = screen();
        int w = Math.min(dp(340), s.x - dp(16));
        int h = Math.min(dp(500), (int) (s.y * 0.7));
        panelLp = overlayParams(w, h, true);
        panelLp.x = (s.x - w) / 2;
        panelLp.y = dp(64);
    }

    private TextView headerBtn(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.parseColor("#E8EBF0"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return b;
    }

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    private void showPanel() {
        if (panelShown) return;
        wm.addView(panel, panelLp);
        panelShown = true;
        // keep the bubble above the panel
        safeRemove(bubble);
        wm.addView(bubble, bubbleLp);
    }

    private void hidePanel() {
        if (!panelShown) return;
        safeRemove(panel);
        panelShown = false;
    }

    // ---------- helpers ----------

    private WindowManager.LayoutParams overlayParams(int w, int h, boolean focusable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (focusable) {
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        }
        return lp;
    }

    @SuppressWarnings("deprecation")
    private Point screen() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect r = wm.getCurrentWindowMetrics().getBounds();
            return new Point(r.width(), r.height());
        }
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new Point(m.widthPixels, m.heightPixels);
    }

    private void safeRemove(View v) {
        try {
            if (v != null) wm.removeViewImmediate(v);
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class Bridge {
        @JavascriptInterface
        public void copy(String text) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("March layers", text));
            });
        }
    }
}
