package com.harley.marchhelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads your generals from the in-game Generals list (Detail mode) as you scroll.
 *
 * Each general shows "Lv37 Maria Carolina ( Ground Troop Commander )", then a row of stars, then
 * "Leadership:". We read the level and name from the text, and count the stars from their colours
 * in the strip just above "Leadership:" (gold = star, red = ascension star; empty stars are grey).
 * The list's "167/177" counter tells us how many generals you own, so we know when the scan is
 * complete. Each general is kept once (by name); level and stars take the value read most often.
 *
 * Pure Java (no Android), so it can be tested on its own.
 */
public class GeneralScan {

    static class Gen {
        String name;
        final Map<Integer, Integer> lv = new HashMap<>(), stars = new HashMap<>(), red = new HashMap<>();
    }

    static class Seen {
        String name;
        int level, stars = -1, red = -1;
    }

    private final List<Gen> gens = new ArrayList<>();
    private final Map<Integer, Integer> ownedVotes = new HashMap<>();

    public void reset() {
        gens.clear();
        ownedVotes.clear();
    }

    /** One frame: words with boxes, plus the frame's pixels (ARGB) for star colours. */
    static List<Seen> extract(List<ScoutTally.W> words, int[] px, int w, int h, int[] ownedOut) {
        List<Seen> out = new ArrayList<>();
        for (ScoutTally.W wd : words) {
            // "167/177" near the top: generals owned / slots
            if (ownedOut != null && wd.t.matches("\\d{1,3}/\\d{1,3}") && wd.top < h * 0.35) {
                String[] p = wd.t.split("/");
                int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
                if (a > 0 && a <= b) ownedOut[0] = a;
            }
            int lvl = level(wd.t);
            if (lvl <= 0) continue;
            // name: words to the right on the same row, up to "(" (role / status follows)
            StringBuilder name = new StringBuilder();
            int prevR = wd.r;
            for (ScoutTally.W n : rowRight(words, wd)) {
                if (n.l - prevR > wd.h() * 3) break;
                String t = n.t;
                int paren = t.indexOf('(');
                if (paren == 0) break;
                if (paren > 0) t = t.substring(0, paren);
                t = t.replaceAll("[^A-Za-z0-9.'\\-|]", "");
                if (t.matches("[IlVX1|]{2,5}")) t = t.replaceAll("[l1|]", "I"); // "Ill" -> "III"
                t = t.replaceAll("[^A-Za-z.'\\-]", "");
                if (!t.isEmpty()) name.append(name.length() > 0 ? " " : "").append(t);
                prevR = n.r;
                if (paren > 0) break;
            }
            String nm = name.toString().replaceAll("[.\\-']+$", "").trim();
            if (nm.length() < 3 || !nm.matches("[A-Za-z].*")) continue;
            Seen s = new Seen();
            s.name = nm;
            s.level = lvl;
            // stars: strip just above the first "Leadership" label under this name
            ScoutTally.W lead = null;
            for (ScoutTally.W o : words) {
                if (!o.t.toLowerCase(Locale.US).startsWith("leadership")) continue;
                if (o.top <= wd.bot || o.top - wd.bot > wd.h() * 9 || Math.abs(o.l - wd.l) > wd.h() * 3) continue;
                if (lead == null || o.top < lead.top) lead = o;
            }
            if (lead != null && px != null) {
                int[] c = countStars(px, w, h, wd.l - wd.h() / 2, wd.l + wd.h() * 12,
                        lead.top - (int) (wd.h() * 2.4), lead.top - wd.h() / 5);
                if (c != null) { s.stars = c[0] + c[1]; s.red = c[1]; }
            }
            out.add(s);
        }
        return out;
    }

    /** Count star blobs in a box: returns {gold, red}, or null if the box is off screen. */
    static int[] countStars(int[] px, int w, int h, int x0, int x1, int y0, int y1) {
        x0 = Math.max(0, x0); x1 = Math.min(w - 1, x1);
        if (y0 < 0 || y1 >= h || y1 - y0 < 4 || x1 - x0 < 10) return null;
        int cols = x1 - x0 + 1;
        int[] kind = new int[cols]; // 0 none, 1 gold, 2 red
        for (int x = x0; x <= x1; x++) {
            int g = 0, r = 0;
            for (int y = y0; y <= y1; y++) {
                int p = px[y * w + x], R = (p >> 16) & 255, G = (p >> 8) & 255, B = p & 255;
                if (R > 170 && G > 120 && B < 110 && R - B > 90) g++;
                else if (R > 120 && G < 90 && B < 100 && R - G > 60) r++;
            }
            int need = Math.max(2, (y1 - y0) / 8);
            kind[x - x0] = g >= need && g >= r ? 1 : r >= need ? 2 : 0;
        }
        int gold = 0, red = 0, minW = Math.max(3, (y1 - y0) / 4);
        for (int i = 0; i < cols; ) {
            if (kind[i] == 0) { i++; continue; }
            int j = i, gc = 0, rc = 0;
            while (j < cols && kind[j] != 0) { if (kind[j] == 1) gc++; else rc++; j++; }
            if (j - i >= minW) { if (gc >= rc) gold++; else red++; }
            i = j;
        }
        if (gold + red > 5) return null; // something else in the strip
        return new int[]{gold, red};
    }

    private static List<ScoutTally.W> rowRight(List<ScoutTally.W> words, ScoutTally.W a) {
        List<ScoutTally.W> row = new ArrayList<>();
        int tol = (int) (a.h() * 0.6);
        for (ScoutTally.W w : words)
            if (w != a && w.l >= a.r - 2 && Math.abs(w.cy() - a.cy()) <= tol) row.add(w);
        row.sort((x, y) -> Integer.compare(x.l, y.l));
        return row;
    }

    /** "Lv37" / "LV 37" / "Lv1" -> level 1..60; otherwise 0. */
    static int level(String t) {
        String s = t.trim();
        if (!s.matches("(?i)^[l1I][vy][.:]?[0-9lIOo]{1,2}$")) return 0;
        String d = s.replaceFirst("(?i)^[l1I][vy][.:]?", "").replaceAll("(?i)[li|]", "1").replaceAll("(?i)o", "0");
        if (!d.matches("\\d{1,2}")) return 0;
        int v = Integer.parseInt(d);
        return v >= 1 && v <= 60 ? v : 0;
    }

    /** Add one frame's reads. Returns true if anything changed. */
    public boolean feed(List<Seen> seen, int owned) {
        boolean changed = false;
        if (owned > 0) {
            int before = best(ownedVotes);
            ownedVotes.merge(owned, 1, Integer::sum);
            changed |= best(ownedVotes) != before;
        }
        for (Seen s : seen) {
            Gen g = null;
            String key = s.name.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
            for (Gen x : gens)
                if (ScoutTally.similar(x.name.toLowerCase(Locale.US).replaceAll("[^a-z]", ""), key)) { g = x; break; }
            if (g == null) { g = new Gen(); g.name = s.name; gens.add(g); changed = true; }
            int l0 = best(g.lv), s0 = best(g.stars), r0 = best(g.red);
            g.lv.merge(s.level, 1, Integer::sum);
            if (s.stars >= 0) { g.stars.merge(s.stars, 1, Integer::sum); g.red.merge(s.red, 1, Integer::sum); }
            changed |= best(g.lv) != l0 || best(g.stars) != s0 || best(g.red) != r0;
        }
        return changed;
    }

    private static int best(Map<Integer, Integer> m) {
        int b = -1, n = -1;
        for (Map.Entry<Integer, Integer> e : m.entrySet())
            if (e.getValue() > n || (e.getValue() == n && e.getKey() > b)) { b = e.getKey(); n = e.getValue(); }
        return b;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"owned\":").append(best(ownedVotes)).append(",\"g\":[");
        for (int i = 0; i < gens.size(); i++) {
            Gen g = gens.get(i);
            sb.append(i > 0 ? "," : "").append("{\"n\":\"").append(g.name.replaceAll("[\"\\\\]", ""))
                    .append("\",\"l\":").append(best(g.lv)).append(",\"s\":").append(best(g.stars))
                    .append(",\"r\":").append(best(g.red)).append('}');
        }
        return sb.append("]}").toString();
    }
}
