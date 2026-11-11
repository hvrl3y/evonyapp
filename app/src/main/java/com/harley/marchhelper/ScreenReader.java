package com.harley.marchhelper;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "selected / max" troop counter (e.g. "189,151 / 1,927,850") from the screen
 * while a march preset or march screen is open. All recognition runs on the phone.
 */
public class ScreenReader {

    public interface Listener {
        /** Called on a background thread with the march max and the selected count. */
        void onMarch(long max, long selected);

        /** Called on a background thread with troop totals read from a scout report. */
        void onScout(long ground, long ranged, long mounted, long siege);
    }

    private static final long INTERVAL_MS = 1500;
    private static final int MAX_WIDTH = 1080;

    // "189,151 / 1,927,850" — tolerant of OCR reading the slash as | I l \
    private static final Pattern PAIR = Pattern.compile(
            "(\\d{1,3}(?:[,.]\\d{3})+|\\d{4,})\\s*[/|Il\\\\]\\s*(\\d{1,3}(?:[,.]\\d{3})+|\\d{4,})");

    private final MediaProjection projection;
    private final Listener listener;
    private final TextRecognizer recognizer;
    private final HandlerThread thread;
    private final Handler handler;
    private ImageReader reader;
    private VirtualDisplay display;
    private volatile boolean busy = false;
    private volatile boolean paused = false;
    private volatile boolean running = false;
    private int width, height;

    public ScreenReader(MediaProjection projection, Listener listener) {
        this.projection = projection;
        this.listener = listener;
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        this.thread = new HandlerThread("marchitect-ocr");
        thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public void start(int screenW, int screenH, int dpi) {
        float scale = screenW > MAX_WIDTH ? (float) MAX_WIDTH / screenW : 1f;
        width = Math.round(screenW * scale);
        height = Math.round(screenH * scale);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("marchitect-reader", width, height,
                Math.max(1, Math.round(dpi * scale)),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
        running = true;
        handler.postDelayed(tick, 600);
    }

    /** Skip reading while the panel is hidden, to save battery. */
    public void setPaused(boolean p) {
        paused = p;
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        try { recognizer.close(); } catch (Exception ignored) { }
        thread.quitSafely();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    if (!paused && !busy) {
                        Bitmap bmp = toBitmap(img);
                        img.close();
                        recognize(bmp);
                    } else {
                        img.close();
                    }
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private Bitmap toBitmap(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int padded = rowStride / pixelStride;
        Bitmap full = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buf);
        if (padded == width) return full;
        Bitmap crop = Bitmap.createBitmap(full, 0, 0, width, height);
        full.recycle();
        return crop;
    }

    private void recognize(Bitmap bmp) {
        busy = true;
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> handler.post(() -> parse(text)))
                .addOnCompleteListener(t -> {
                    busy = false;
                    bmp.recycle();
                });
    }

    // Words that appear in Evony troop names, by type. Matched as word starts, lower case.
    private static final String[][] KEYWORDS = {
            /* ground  */ {"ground", "guard", "infantry", "swordsm", "spearm", "pikem", "warrior", "legion", "hoplite", "phalanx", "conscript", "militia", "halberd"},
            /* ranged  */ {"ranged", "archer", "bowm", "crossbow", "marksm", "slinger", "longbow", "sharpshoot", "musket"},
            /* mounted */ {"mounted", "cavalry", "rider", "knight", "horse", "lancer", "cataphract", "hussar", "cuirassier", "dragoon"},
            /* siege   */ {"siege", "catapult", "ballista", "trebuchet", "battering", "onager", "cannon", "mangonel", "machine", "bombard"},
    };
    private static final Pattern COUNT = Pattern.compile("[x×]?(\\d{1,3}(?:[,.]\\d{3})+|\\d+)[,.;:]?");

    private void parse(Text text) {
        String all = text.getText().toLowerCase(Locale.US);
        List<String> rows = rows(text);
        if (all.contains("scout")) parseScout(rows);
        // Only trust march numbers on troop / march screens.
        if (all.contains("preset") || all.contains("troops") || all.contains("march")) parseMarch(rows);
    }

    private void parseScout(List<String> rows) {
        long[] tot = new long[4];
        for (String row : rows) {
            // Walk the words left to right. A troop word sets the current type; the largest
            // number that follows it (or comes just before the first one) is that troop's count.
            // This keeps two troop cards that sit side by side on one line apart.
            int cur = -1;
            long pending = 0, segMax = 0;
            for (String w : row.split("\\s+")) {
                if (w.isEmpty()) continue;
                int t = typeOf(w.toLowerCase(Locale.US));
                if (t >= 0) {
                    if (cur >= 0 && segMax >= 10) tot[cur] += segMax;
                    else if (cur < 0 && pending >= 10) segMax = pending;
                    if (cur >= 0) segMax = 0;
                    cur = t;
                    continue;
                }
                Matcher m = COUNT.matcher(w);
                if (m.matches()) {
                    long n = num(w);
                    if (cur < 0) pending = Math.max(pending, n);
                    else segMax = Math.max(segMax, n);
                }
            }
            if (cur >= 0 && segMax >= 10) tot[cur] += segMax;
        }
        if (tot[0] + tot[1] + tot[2] + tot[3] > 0) listener.onScout(tot[0], tot[1], tot[2], tot[3]);
    }

    private static int typeOf(String word) {
        for (int t = 0; t < 4; t++)
            for (String k : KEYWORDS[t])
                if (word.startsWith(k)) return t;
        return -1;
    }

    /** Rebuild visual rows from words, so text on one line of the screen is read together. */
    private List<String> rows(Text text) {
        List<Text.Element> words = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks())
            for (Text.Line l : b.getLines())
                words.addAll(l.getElements());

        List<String> out = new ArrayList<>();
        boolean[] used = new boolean[words.size()];
        for (int i = 0; i < words.size(); i++) {
            if (used[i]) continue;
            Rect a = words.get(i).getBoundingBox();
            if (a == null) continue;
            List<Text.Element> row = new ArrayList<>();
            for (int j = i; j < words.size(); j++) {
                Rect b = words.get(j).getBoundingBox();
                if (b == null || used[j]) continue;
                int tol = Math.max(a.height(), b.height()) / 2;
                if (Math.abs(a.centerY() - b.centerY()) <= tol) {
                    row.add(words.get(j));
                    used[j] = true;
                }
            }
            row.sort((x, y) -> Integer.compare(x.getBoundingBox().left, y.getBoundingBox().left));
            StringBuilder sb = new StringBuilder();
            for (Text.Element e : row) sb.append(e.getText()).append(' ');
            out.add(sb.toString());
        }
        return out;
    }

    private void parseMarch(List<String> rows) {
        long bestMax = -1, bestSel = -1;
        for (String row : rows) {
            Matcher m = PAIR.matcher(row);
            while (m.find()) {
                long sel = num(m.group(1)), max = num(m.group(2));
                if (max >= 1000 && sel <= max && max > bestMax) {
                    bestMax = max;
                    bestSel = sel;
                }
            }
        }
        if (bestMax > 0) listener.onMarch(bestMax, bestSel);
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return -1;
        }
    }
}
