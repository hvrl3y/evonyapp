package com.harley.marchhelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds one scout report out of many screen reads.
 *
 * Evony lists troops as rows of "Lv15  Dragon Vanguards  11,414,567". Each read, we find every
 * "Lv##" word, take the words to its right up to the first count (so map text to the left or
 * right of the report is never mixed in), and file the troop under its tier + name. Seeing the
 * same troop again (scrolling back up) updates that one entry instead of adding it twice; its
 * count is the value read most often, which irons out an odd misread digit. Rows cut off at the
 * edge of the list are skipped. The report's own "Troops 2,112,680,392" total is read too, so we
 * can show how much of the army has been scanned.
 *
 * Pure Java (no Android), so it can be tested on its own.
 */
public class ScoutTally {

    static final long SESSION_GAP_MS = 8000;
    static final String[] TYPE_NAMES = {"ground", "ranged", "mounted", "siege"};

    // Ranged / mounted / siege are recognised by name; anything else in the troop list is ground
    // (ground troop names change every tier: Warriors, Conscripts, ... Vanguards).
    private static final String[][] STARTS = {
            /* ground  */ {},
            /* ranged  */ {"archer", "bowm", "crossbow", "arbalest", "marksm", "slinger", "longbow", "sharpshoot", "musket", "ranger"},
            /* mounted */ {"cavalr", "cavalier", "rider", "knight", "horsem", "lancer", "cataphract", "hussar", "cuirassier", "dragoon", "mounted"},
            /* siege   */ {"catapult", "ballist", "trebuch", "battering", "onager", "cannon", "mangonel", "bombard", "siege", "scorpion"},
    };

    /** A word on screen with its box (pixels). */
    public static class W {
        final String t;
        final int l, r, top, bot;

        public W(String t, int l, int top, int r, int bot) {
            this.t = t; this.l = l; this.r = r; this.top = top; this.bot = bot;
        }

        int cy() { return (top + bot) / 2; }
        int h() { return Math.max(1, bot - top); }
    }

    static class Line {
        int tier, type;
        String name;
        long count;
    }

    static class Read {
        final List<Line> lines = new ArrayList<>();
        long total = -1;
    }

    static class Entry {
        int tier, type;
        String name;
        final Map<Long, Integer> votes = new HashMap<>();

        long count() { return best(votes); }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, Integer> totalVotes = new HashMap<>();
    private long lastSeen = 0;
    private int session = 0;
    private boolean active = false;

    public boolean isActive(long now) {
        return active && now - lastSeen < SESSION_GAP_MS;
    }

    public void reset() {
        entries.clear();
        totalVotes.clear();
        active = false;
        lastSeen = 0;
    }

    // ------------------------------------------------------------------ reading one frame

    /** Pull troop rows and the "Troops" total out of one frame's words. */
    static Read extract(List<W> words) {
        Read out = new Read();

        // 1. find the "Lv##" words (tolerating Lv 15, LV15, Lvl5, Lv.15 ...)
        List<int[]> lv = new ArrayList<>(); // {wordIndex, tier, rightEdge}
        for (int i = 0; i < words.size(); i++) {
            W w = words.get(i);
            int tier = lvTier(w.t);
            int right = w.r;
            if (tier == 0 && w.t.matches("(?i)[l1I][vy]l?[.:]?")) { // "Lv" then "15" as a separate word
                W n = nextRight(words, w);
                if (n != null && n.l - w.r < w.h()) {
                    tier = parseTierDigits(n.t);
                    right = n.r;
                }
            }
            if (tier > 0) lv.add(new int[]{i, tier, right});
        }
        if (!lv.isEmpty()) {
            // rows cut off at the top/bottom of the list read shorter than the rest: skip them
            List<Integer> hs = new ArrayList<>();
            for (int[] x : lv) hs.add(words.get(x[0]).h());
            hs.sort(Integer::compare);
            int median = hs.get(hs.size() / 2);

            for (int[] x : lv) {
                W start = words.get(x[0]);
                if (start.h() < median * 0.75) continue;
                List<W> row = rightOf(words, start, x[2]);
                List<String> name = new ArrayList<>();
                long count = -1;
                int prevR = x[2];
                for (W w : row) {
                    if (w.l - prevR > start.h() * 16) break; // too far: not part of this row
                    long n = countOf(w.t);
                    if (n >= 0) {
                        if (w.h() >= median * 0.75) count = n;
                        break;
                    }
                    String lw = w.t.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
                    if (!lw.isEmpty()) name.add(lw);
                    prevR = w.r;
                }
                if (count < 0 || name.isEmpty()) continue;
                Line line = new Line();
                line.tier = x[1];
                line.name = String.join(" ", name);
                line.type = typeOf(name);
                line.count = count;
                out.lines.add(line);
            }
        }

        // 2. the report total: "Troops   2,112,680,392"
        for (W w : words) {
            if (!w.t.equalsIgnoreCase("troops")) continue;
            for (W n : rightOf(words, w, w.r)) {
                long v = countOf(n.t);
                if (v >= 0) {
                    if (n.t.contains(",") || n.t.contains(".")) out.total = v;
                    break;
                }
            }
        }
        return out;
    }

    private static List<W> rightOf(List<W> words, W anchor, int fromX) {
        List<W> row = new ArrayList<>();
        int tol = (int) (anchor.h() * 0.6);
        for (W w : words) {
            if (w == anchor || w.l < fromX - 2) continue;
            if (Math.abs(w.cy() - anchor.cy()) <= tol) row.add(w);
        }
        row.sort(Comparator.comparingInt(a -> a.l));
        return row;
    }

    private static W nextRight(List<W> words, W anchor) {
        List<W> r = rightOf(words, anchor, anchor.r);
        return r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------------ building the report

    /**
     * Feed one frame. {@code scoutScreen}: the frame shows "Scout Report".
     * Returns true if the report changed.
     */
    public boolean feed(Read read, boolean scoutScreen, long now) {
        if (!scoutScreen) return false;
        long curTotal = best(totalVotes);
        boolean newReport = !isActive(now)
                || (read.total > 0 && curTotal > 0 && Math.abs(read.total - curTotal) > curTotal / 100);
        if (newReport) {
            entries.clear();
            totalVotes.clear();
            session++;
            active = true;
        }
        lastSeen = now;
        boolean changed = newReport;
        if (read.total > 0) {
            long before = best(totalVotes);
            totalVotes.merge(read.total, 1, Integer::sum);
            changed |= best(totalVotes) != before;
        }
        for (Line l : read.lines) changed |= add(l);
        return changed;
    }

    private boolean add(Line l) {
        Entry match = null;
        for (Entry e : entries) {
            if (e.tier == l.tier && similar(e.name, l.name)) { match = e; break; }
        }
        boolean isNew = match == null;
        if (isNew) {
            match = new Entry();
            match.tier = l.tier;
            match.name = l.name;
            match.type = l.type;
            entries.add(match);
        }
        long before = match.count();
        match.votes.merge(l.count, 1, Integer::sum);
        return isNew || match.count() != before;
    }

    // ------------------------------------------------------------------ helpers

    static long best(Map<Long, Integer> votes) {
        long best = 0;
        int n = -1;
        for (Map.Entry<Long, Integer> e : votes.entrySet()) {
            if (e.getValue() > n || (e.getValue() == n && e.getKey() > best)) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    static boolean similar(String a, String b) {
        if (a.equals(b)) return true;
        int max = Math.max(1, Math.max(a.length(), b.length()) / 6);
        return Math.abs(a.length() - b.length()) <= max && lev(a, b) <= max;
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    static int typeOf(List<String> nameWords) {
        for (int t = 1; t < 4; t++)
            for (String w : nameWords)
                for (String k : STARTS[t])
                    if (w.startsWith(k)) return t;
        return 0; // ground
    }

    /** "Lv15", "LV15", "Lv.15", "Lvl5", "Lv1S" -> 15; otherwise 0. */
    static int lvTier(String w) {
        String s = w.trim();
        if (!s.matches("(?i)^[l1I][vy][.:]?[0-9lIOoSs|]{1,2}$")) return 0;
        return parseTierDigits(s.replaceFirst("(?i)^[l1I][vy][.:]?", ""));
    }

    static int parseTierDigits(String d) {
        String s = d.replaceAll("[lI|]", "1").replaceAll("[Oo]", "0").replaceAll("[Ss]", "5");
        if (!s.matches("\\d{1,2}")) return 0;
        int v = Integer.parseInt(s);
        return v >= 1 && v <= 16 ? v : 0;
    }

    /** "1,735,813" / "1.735.813" / "120" -> value; anything else (incl. "35K", "Lv15") -> -1. */
    static long countOf(String w) {
        String s = w.trim().replaceFirst("^[xX×]", "");
        if (s.matches("\\d{1,3}(,\\d{3})+") || s.matches("\\d{1,3}(\\.\\d{3})+") || s.matches("\\d{1,3}"))
            return Long.parseLong(s.replaceAll("\\D", ""));
        return -1;
    }

    /** JSON for the panel. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"session\":").append(session)
                .append(",\"total\":").append(best(totalVotes)).append(",\"e\":[");
        boolean first = true;
        for (Entry e : entries) {
            long c = e.count();
            if (c <= 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":\"").append(e.name).append("\",\"t\":\"").append(TYPE_NAMES[e.type])
                    .append("\",\"r\":").append(e.tier).append(",\"c\":").append(c).append('}');
        }
        return sb.append("]}").toString();
    }
}
