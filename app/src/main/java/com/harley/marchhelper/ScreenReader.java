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

        /** Called on a background thread with the scout report built so far (JSON). */
        void onScout(String json);

        /** Called with your own troops (JSON) while "Scan my troops" is on. */
        void onMine(String json);

        /** Called with the generals read so far (JSON) while the generals scan is on. */
        void onGenerals(String json);

        /** Monarch Detail values read so far (JSON) while the Buffs scan is on. */
        void onBuffs(String json);

        /** Battle report Troop Buff values read so far (JSON) while the Reports scan is on. */
        void onReport(String json);
    }

    private static final long INTERVAL_MS = 1500;
    private static final long FAST_MS = 600; // while a scout report is open, to keep up with scrolling
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
    private final ScoutTally tally = new ScoutTally();
    private final ScoutTally mine = new ScoutTally(Long.MAX_VALUE / 4);
    private volatile boolean mineScan = false;
    private final GeneralScan gens = new GeneralScan();
    private volatile boolean genScan = false;
    private final BuffScan buffs = new BuffScan();
    private volatile boolean buffScan = false;
    private final ReportScan report = new ReportScan();
    private volatile boolean reportScan = false;

    /** Read the Monarch Detail (Military) screen while you scroll it. */
    public void setBuffScan(boolean on) {
        handler.post(() -> {
            if (on && !buffScan) buffs.reset();
            buffScan = on;
        });
    }

    /** Read a battle report's Troop Buff tab while you scroll it. */
    public void setReportScan(boolean on) {
        handler.post(() -> {
            if (on && !reportScan) report.reset();
            reportScan = on;
        });
    }

    /** Read your generals from the in-game Generals list (Detail mode) while you scroll it. */
    public void setGenScan(boolean on) {
        handler.post(() -> {
            if (on && !genScan) gens.reset();
            genScan = on;
        });
    }

    /** Read your own troop list (any screen with "Lv## name count" rows that isn't a scout report). */
    public void setMineScan(boolean on) {
        handler.post(() -> {
            if (on && !mineScan) mine.reset();
            mineScan = on;
        });
    }

    /** Forget the current scout report (the next one starts fresh). */
    public void resetScout() {
        handler.post(() -> {
            tally.reset();
            listener.onScout(tally.toJson());
        });
    }

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
            handler.postDelayed(this, mineScan || genScan || buffScan || reportScan || tally.isActive(System.currentTimeMillis()) ? FAST_MS : INTERVAL_MS);
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
                // keep the frame until parse() is done: the generals scan reads star colours from it
                .addOnSuccessListener(text -> handler.post(() -> {
                    try { parse(text, bmp); } catch (Exception ignored) { }
                    bmp.recycle();
                    busy = false;
                }))
                .addOnFailureListener(e -> { bmp.recycle(); busy = false; });
    }

    private List<ScoutTally.W> words(Text text) {
        List<ScoutTally.W> ws = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks())
            for (Text.Line l : b.getLines())
                for (Text.Element e : l.getElements()) {
                    Rect r = e.getBoundingBox();
                    if (r != null) ws.add(new ScoutTally.W(e.getText(), r.left, r.top, r.right, r.bottom));
                }
        return ws;
    }

    private void parse(Text text, Bitmap bmp) {
        String all = text.getText().toLowerCase(Locale.US);
        if (genScan && all.contains("leadership")) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            int[] owned = {-1};
            List<GeneralScan.Seen> seen = GeneralScan.extract(words(text), px, w, h, owned);
            if (gens.feed(seen, owned[0])) listener.onGenerals(gens.toJson());
            return;
        }
        if (buffScan && (all.contains("monarch detail") || all.contains("military"))) {
            if (buffs.feed(BuffScan.extract(words(text), bmp.getWidth()))) listener.onBuffs(buffs.toJson());
            return;
        }
        if (reportScan && (all.contains("troop buff") || all.contains("allied troops") || all.contains("against enemy")
                || all.contains("attack+") || all.contains("defense-") || all.contains("main general"))) {
            if (report.feed(ReportScan.extract(words(text), bmp.getWidth()))) listener.onReport(report.toJson());
            return;
        }
        boolean scout = all.contains("scout report") || all.contains("scout");
        if (mineScan && !scout) {
            List<ScoutTally.W> ws = new ArrayList<>();
            for (Text.TextBlock b : text.getTextBlocks())
                for (Text.Line l : b.getLines())
                    for (Text.Element e : l.getElements()) {
                        Rect r = e.getBoundingBox();
                        if (r != null) ws.add(new ScoutTally.W(e.getText(), r.left, r.top, r.right, r.bottom));
                    }
            ScoutTally.Read read = ScoutTally.extract(ws);
            if (!read.lines.isEmpty() && mine.feed(read, true, System.currentTimeMillis())) listener.onMine(mine.toJson());
        }
        if (scout) {
            List<ScoutTally.W> ws = new ArrayList<>(), ls = new ArrayList<>();
            for (Text.TextBlock b : text.getTextBlocks())
                for (Text.Line l : b.getLines()) {
                    Rect lr = l.getBoundingBox();
                    if (lr != null) ls.add(new ScoutTally.W(l.getText(), lr.left, lr.top, lr.right, lr.bottom));
                    for (Text.Element e : l.getElements()) {
                        Rect r = e.getBoundingBox();
                        if (r != null) ws.add(new ScoutTally.W(e.getText(), r.left, r.top, r.right, r.bottom));
                    }
                }
            ScoutTally.Read read = ScoutTally.extract(ws);
            ScoutTally.extractGenerals(ls, read);
            if (tally.feed(read, true, System.currentTimeMillis()))
                listener.onScout(tally.toJson());
            return;
        }
        // Only trust march numbers on troop / march screens.
        if (all.contains("preset") || all.contains("troops") || all.contains("march")) parseMarch(rows(text));
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
