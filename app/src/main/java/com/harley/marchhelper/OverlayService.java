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
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
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
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating overlay: a bubble that opens a small menu (Layers / Counter), and a panel for each.
 * No overlay window takes input focus unless you're typing in it, so Evony keeps getting your taps.
 */
public class OverlayService extends Service {

    static final String ACTION_STOP = "com.harley.marchhelper.STOP";
    static final String ACTION_PROJECTION = "com.harley.marchhelper.PROJECTION";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private static final String CHANNEL = "overlay";
    private static final int NOTIF_ID = 1;
    private static final int HEADER_DP = 30;
    private static final int BUBBLE_DP = 50;

    // Marchitect palette (iOS dark)
    private static final int C_BG = Color.parseColor("#000000");
    private static final int C_CARD = Color.parseColor("#1C1C1E");
    private static final int C_LINE = Color.parseColor("#545458");
    private static final int C_TINT = Color.parseColor("#F2B544");
    private static final int C_DIM = Color.parseColor("#99EBEBF5");
    private static final int C_RED = Color.parseColor("#FF453A");

    private WindowManager wm;
    private Handler main;
    private SharedPreferences prefs;

    // bubble + its menu
    private ImageView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private LinearLayout menu;
    private WindowManager.LayoutParams menuLp;
    private boolean menuShown = false;
    private View menuLayers, menuScout, menuMuster;

    // panel
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private WebView web;
    private TextView title, expandBtn;
    private boolean panelShown = false;
    private boolean keyboard = false;
    private String view = "layers";           // layers | scout | muster
    private boolean expanded = false;
    private int contentHeightDp = 200;

    // drag-to-close target
    private TextView trash;
    private WindowManager.LayoutParams trashLp;
    private boolean trashShown = false;

    // auto-read
    private MediaProjection projection;
    private ScreenReader reader;
    private long lastMax = -1;
    private String lastScout = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("overlay", MODE_PRIVATE);
        view = prefs.getString("view", "layers");
        if (!view.equals("scout") && !view.equals("muster")) view = "layers";
        expanded = prefs.getBoolean("exp_" + view, false);
        goForeground(false);
        createBubble();
        createMenu();
        createPanel();
        createTrash();
        // Auto-read is always on: ask for screen capture as soon as the overlay starts.
        main.postDelayed(this::requestCapture, 300);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_STOP.equals(intent.getAction())) stopSelf();
            else if (ACTION_PROJECTION.equals(intent.getAction())) onProjectionResult(intent);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        safeRemove(bubble);
        if (menuShown) safeRemove(menu);
        if (panelShown) safeRemove(panel);
        if (trashShown) safeRemove(trash);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------------- notification ----------------

    private void goForeground(boolean capturing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Marchitect overlay is running")
                .setContentText(capturing ? "Reading march sizes and scout reports." : "Tap the bubble for Layers and Counter.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (capturing) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIF_ID, n, type);
        } else if (Build.VERSION.SDK_INT >= 29 && capturing) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------------- auto-read (screen capture + on-device OCR) ----------------

    private void requestCapture() {
        startActivity(new Intent(this, CaptureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @SuppressWarnings("deprecation")
    private void onProjectionResult(Intent intent) {
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (code != android.app.Activity.RESULT_OK || data == null) {
            pushAutoState(false);
            return;
        }
        try {
            goForeground(true); // must happen before getMediaProjection on Android 14+
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> stopCapture());
                }
            }, main);
            Point s = screen();
            reader = new ScreenReader(projection, new ScreenReader.Listener() {
                @Override
                public void onMarch(long max, long sel) {
                    main.post(() -> {
                        if (max == lastMax) return;
                        lastMax = max;
                        js("window.onMarchRead&&onMarchRead(" + max + "," + sel + ")");
                    });
                }

                @Override
                public void onMine(String json) {
                    main.post(() -> js("window.onMineData&&onMineData(" + json + ")"));
                }

                @Override
                public void onScout(String json) {
                    main.post(() -> {
                        if (json.equals(lastScout)) return;
                        lastScout = json;
                        js("window.onScoutData&&onScoutData(" + json + ")");
                    });
                }
            });
            reader.start(s.x, s.y, getResources().getDisplayMetrics().densityDpi);
            lastMax = -1;
            pushAutoState(true);
        } catch (Exception e) {
            stopCapture();
        }
    }

    private void stopCapture() {
        boolean was = reader != null || projection != null;
        if (reader != null) { reader.stop(); reader = null; }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null;
        }
        if (was) {
            try { goForeground(false); } catch (Exception ignored) { }
        }
        pushAutoState(false);
    }

    private void pushAutoState(boolean on) {
        js("window.onAutoState&&onAutoState(" + on + ")");
    }

    private void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---------------- bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private void createBubble() {
        bubble = new ImageView(this);
        bubble.setImageResource(R.drawable.bubble);
        bubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubble.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        bubble.setClipToOutline(true);
        bubble.setElevation(dp(6));

        bubbleLp = overlayParams(dp(BUBBLE_DP), dp(BUBBLE_DP));
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
                        startX = bubbleLp.x; startY = bubbleLp.y;
                        downX = e.getRawX(); downY = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                            hideMenu();
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
                            if (menuShown) hideMenu(); else showMenu();
                        } else {
                            boolean kill = overTrash();
                            showTrash(false);
                            if (kill) stopSelf(); else snapToEdge();
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
            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) { }
        });
        a.start();
    }

    // ---------------- menu (drops out of the bubble, like an auto-clicker bar) ----------------

    private void createMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EB1C1C1E"));
        bg.setStroke(Math.max(1, dp(1) / 2), C_LINE);
        bg.setCornerRadius(dp(14));
        menu.setBackground(bg);
        menu.setElevation(dp(8));
        menuLayers = menuItem(R.drawable.badge_layers, "Layers", "layers");
        menuScout = menuItem(R.drawable.badge_scout, "Scout", "scout");
        menu.addView(menuLayers);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.topMargin = dp(4);
        menu.addView(menuScout, gap);
        menuMuster = menuItem(R.drawable.badge_muster, "Muster", "muster");
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap2.topMargin = dp(4);
        menu.addView(menuMuster, gap2);
        menuLp = overlayParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View menuItem(int icon, String label, String target) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(3));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        item.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setGravity(Gravity.CENTER);
        item.addView(t);
        item.setOnClickListener(v -> {
            hideMenu();
            if (panelShown && view.equals(target)) hidePanel();
            else openView(target);
        });
        return item;
    }

    private void paintMenu() {
        for (View v : new View[]{menuLayers, menuScout, menuMuster}) {
            boolean on = panelShown && ((v == menuLayers && view.equals("layers")) || (v == menuScout && view.equals("scout"))
                    || (v == menuMuster && view.equals("muster")));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? Color.parseColor("#2EF2B544") : Color.TRANSPARENT);
            if (on) bg.setStroke(dp(1), C_TINT);
            bg.setCornerRadius(dp(10));
            v.setBackground(bg);
        }
    }

    private void showMenu() {
        if (menuShown) return;
        paintMenu();
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = menu.getMeasuredWidth(), mh = menu.getMeasuredHeight();
        Point s = screen();
        boolean left = bubbleLp.x + dp(BUBBLE_DP) / 2 < s.x / 2;
        menuLp.x = left ? bubbleLp.x + dp(BUBBLE_DP + 6) : bubbleLp.x - mw - dp(6);
        menuLp.y = Math.max(0, Math.min(bubbleLp.y + dp(BUBBLE_DP) / 2 - mh / 2, s.y - mh));
        wm.addView(menu, menuLp);
        menuShown = true;
    }

    private void hideMenu() {
        if (!menuShown) return;
        safeRemove(menu);
        menuShown = false;
    }

    // ---------------- drag-to-close target ----------------

    private void createTrash() {
        trash = new TextView(this);
        trash.setText("✕");
        trash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        trash.setTextColor(Color.WHITE);
        trash.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC2C2C2E"));
        trash.setBackground(bg);
        trashLp = overlayParams(dp(64), dp(64));
        trashLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        trashLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashLp.x = 0;
        trashLp.y = dp(48);
    }

    private void showTrash(boolean show) {
        if (show && !trashShown) { wm.addView(trash, trashLp); trashShown = true; }
        else if (!show && trashShown) { safeRemove(trash); trashShown = false; }
    }

    private void highlightTrash(boolean on) {
        GradientDrawable bg = (GradientDrawable) trash.getBackground();
        bg.setColor(on ? C_RED : Color.parseColor("#CC2C2C2E"));
        trash.setScaleX(on ? 1.2f : 1f);
        trash.setScaleY(on ? 1.2f : 1f);
    }

    private boolean overTrash() {
        Point s = screen();
        int bx = bubbleLp.x + dp(BUBBLE_DP / 2), by = bubbleLp.y + dp(BUBBLE_DP / 2);
        int tx = s.x / 2, ty = s.y - dp(48) - dp(32);
        return Math.hypot(bx - tx, by - ty) < dp(80);
    }

    // ---------------- panel ----------------

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void createPanel() {
        panel = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    setKeyboard(false);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    setKeyboard(false);
                    return false;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BG);
        bg.setStroke(Math.max(1, dp(1) / 2), C_LINE);
        bg.setCornerRadius(dp(12));
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setElevation(dp(10));

        // header: title (drag here) · expand · minimize
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(C_CARD);
        header.setPadding(dp(10), 0, 0, 0);

        title = new TextView(this);
        title.setTextColor(C_TINT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(HEADER_DP), 1f));

        expandBtn = headerBtn("›", 20);
        expandBtn.setOnClickListener(v -> setExpanded(!expanded));
        header.addView(expandBtn);

        TextView minimize = headerBtn("—", 14);
        minimize.setOnClickListener(v -> hidePanel());
        header.addView(minimize);

        header.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float dx, dy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = panelLp.x; sy = panelLp.y; dx = e.getRawX(); dy = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = sx + (int) (e.getRawX() - dx);
                        panelLp.y = Math.max(0, sy + (int) (e.getRawY() - dy));
                        if (panelShown) wm.updateViewLayout(panel, panelLp);
                        return true;
                }
                return false;
            }
        });
        panel.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(HEADER_DP)));

        web = new WebView(this);
        web.setBackgroundColor(C_BG);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Overlay");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                pushView();
                pushAutoState(reader != null);
            }
        });
        web.loadUrl("file:///android_asset/overlay.html");
        panel.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Point s = screen();
        panelLp = overlayParams(width(), height());
        panelLp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        panelLp.x = s.x - width() - dp(8);
        panelLp.y = dp(72);
        paintHeader();
    }

    private int width() {
        int w = view.equals("scout") ? (expanded ? 264 : 124) : (expanded ? 300 : 212);
        return Math.min(dp(w), screen().x - dp(16));
    }

    private int height() {
        return Math.min(dp(HEADER_DP + contentHeightDp), (int) (screen().y * (expanded ? 0.75f : 0.6f)));
    }

    private void paintHeader() {
        title.setText(view.equals("scout") ? "Scout" : view.equals("muster") ? "Muster" : "Layers");
        expandBtn.setRotation(expanded ? -90 : 90);
    }

    private void openView(String v) {
        if (!v.equals(view)) {
            view = v;
            expanded = prefs.getBoolean("exp_" + v, false);
            prefs.edit().putString("view", v).apply();
            relayout();
            pushView();
        }
        if (!panelShown) anchorBelowBubble();
        showPanel();
    }

    /** Drop the panel down just below the bubble (or above it if there's no room), on the bubble's side. */
    private void anchorBelowBubble() {
        Point s = screen();
        int bs = dp(BUBBLE_DP);
        boolean left = bubbleLp.x + bs / 2 < s.x / 2;
        panelLp.x = left ? Math.max(0, bubbleLp.x) : Math.max(0, bubbleLp.x + bs - panelLp.width);
        int below = bubbleLp.y + bs + dp(4);
        panelLp.y = below + panelLp.height <= s.y - dp(16) ? below : Math.max(0, bubbleLp.y - panelLp.height - dp(4));
    }

    private void setExpanded(boolean on) {
        expanded = on;
        prefs.edit().putBoolean("exp_" + view, on).apply();
        relayout();
        pushView();
    }

    private void relayout() {
        int oldRight = panelLp.x + panelLp.width;
        panelLp.width = width();
        panelLp.height = height();
        Point s = screen();
        panelLp.x = Math.max(0, Math.min(oldRight - panelLp.width, s.x - panelLp.width));
        panelLp.y = Math.max(0, Math.min(panelLp.y, s.y - panelLp.height));
        paintHeader();
        if (panelShown) wm.updateViewLayout(panel, panelLp);
    }

    private void pushView() {
        js("window.setView&&setView('" + view + "'," + expanded + ")");
    }

    /** Only take focus (and the keyboard) while typing in the panel; otherwise taps go to Evony. */
    private void setKeyboard(boolean on) {
        if (on == keyboard) return;
        keyboard = on;
        if (on) panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (panelShown) wm.updateViewLayout(panel, panelLp);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (on) {
            main.postDelayed(() -> { web.requestFocus(); imm.showSoftInput(web, 0); }, 120);
        } else {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            web.evaluateJavascript("window.dropFocus&&dropFocus()", null);
        }
    }

    private TextView headerBtn(String label, int sp) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(C_DIM);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(HEADER_DP)));
        return b;
    }

    private void showPanel() {
        if (panelShown) return;
        wm.addView(panel, panelLp);
        panelShown = true;
        safeRemove(bubble); // keep the bubble above the panel
        wm.addView(bubble, bubbleLp);
    }

    private void hidePanel() {
        if (!panelShown) return;
        setKeyboard(false);
        safeRemove(panel);
        panelShown = false;
    }

    // ---------------- helpers ----------------

    /** Every overlay window starts unfocusable and is kept out of the screen reader's view. */
    private WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
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
        try { if (v != null) wm.removeViewImmediate(v); } catch (Exception ignored) { }
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

        @JavascriptInterface
        public void setContentHeight(int cssPx) {
            main.post(() -> {
                if (cssPx <= 0 || Math.abs(cssPx - contentHeightDp) < 2) return;
                contentHeightDp = cssPx;
                panelLp.height = height();
                if (panelShown) wm.updateViewLayout(panel, panelLp);
            });
        }

        @JavascriptInterface
        public void requestAutoRead() {
            main.post(() -> { if (reader == null) requestCapture(); });
        }

        @JavascriptInterface
        public boolean isAutoRead() {
            return reader != null;
        }

        @JavascriptInterface
        public void wantKeyboard(boolean on) {
            main.post(() -> setKeyboard(on));
        }

        @JavascriptInterface
        public void setMineScan(boolean on) {
            main.post(() -> {
                if (reader == null) { if (on) requestCapture(); js("window.onScanState&&onScanState(false)"); return; }
                reader.setMineScan(on);
                js("window.onScanState&&onScanState(" + on + ")");
            });
        }

        @JavascriptInterface
        public void resetScout() {
            main.post(() -> {
                lastScout = "";
                if (reader != null) reader.resetScout();
                else js("window.onScoutData&&onScoutData({session:-1,e:[]})");
            });
        }
    }
}
