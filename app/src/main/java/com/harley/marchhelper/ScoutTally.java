package com.harley.marchhelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds one scout report out of many screen reads.
 *
 * Every troop line is remembered by its name, so the same troop seen again (scrolling back up,
 * or the same frame read twice) updates that one entry instead of being added again. Each entry's
 * count is the value read most often, which irons out the odd misread digit. Names that differ by
 * a letter or two (a misread name) are treated as the same troop.
 *
 * Pure Java (no Android), so it can be tested on its own.
 */
public class ScoutTally {

    /** A session ends when no scout text has been seen for this long. */
    static final long SESSION_GAP_MS = 8000;

    static final String[] TYPE_NAMES = {"ground", "ranged", "mounted", "siege"};

    // Word starts that identify a troop type in Evony troop names.
    private static final String[][] STARTS = {
            /* ground  */ {"ground", "guard", "infantr", "sword", "spear", "pike", "warrior", "legion", "hoplite",
                           "phalanx", "conscript", "militia", "halberd", "axem", "berserk", "footm", "sentinel"},
            /* ranged  */ {"ranged", "archer", "bowm", "crossbow", "arbalest", "marksm", "slinger", "longbow",
                           "sharpshoot", "musket", "skirmish"},
            /* mounted */ {"mounted", "cavalr", "rider", "knight", "horsem", "lancer", "cataphract", "hussar",
                           "cuirassier", "dragoon", "cavalier"},
            /* siege   */ {"siege", "catapult", "ballist", "trebuch", "battering", "onager", "cannon", "mangonel",
                           "bombard", "scorpion"},
    };
    // Whole words only (too short to match by start).
    private static final String[][] EXACT = {
            {"guards"}, {"bows"}, {"cav", "horse", "horses"}, {"ram", "rams", "machine", "machines"},
    };
    private static final Set<String> ROMAN = new HashSet<>();
    private static final Map<String, Integer> ROMAN_VAL = new HashMap<>();

    static {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI"};
        for (int i = 0; i < r.length; i++) {
            ROMAN.add(r[i]);
            ROMAN_VAL.put(r[i], i + 1);
        }
    }

    static class Entry {
        String name;
        int type;
        int tier; // 0 = unknown
        final Map<Long, Integer> votes = new HashMap<>();

        long count() {
            long best = 0;
            int bestN = -1;
            for (Map.Entry<Long, Integer> e : votes.entrySet()) {
                // most-read value wins; on a tie prefer the larger number
                if (e.getValue() > bestN || (e.getValue() == bestN && e.getKey() > best)) {
                    best = e.getKey();
                    bestN = e.getValue();
                }
            }
            return best;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private long lastSeen = 0;
    private int session = 0;
    private boolean active = false;

    public boolean isActive(long now) {
        return active && now - lastSeen < SESSION_GAP_MS;
    }

    public void reset() {
        entries.clear();
        active = false;
        lastSeen = 0;
    }

    public int session() {
        return session;
    }

    /**
     * Feed one screen read. {@code scoutText} is whether the frame mentions "scout";
     * {@code otherScreen} is whether it's clearly a different screen (presets, march setup).
     * Returns true if the tally changed.
     */
    public boolean feed(List<String> rows, boolean scoutText, boolean otherScreen, long now) {
        if (otherScreen) return false;
        boolean inSession = isActive(now);
        if (!inSession && !scoutText) return false;
        if (!inSession) { // a new report: start over
            entries.clear();
            session++;
            active = true;
        }

        boolean changed = false, sawTroop = false;
        for (String row : rows) {
            for (Parsed p : parseRow(row)) {
                sawTroop = true;
                changed |= add(p);
            }
        }
        if (scoutText || sawTroop) lastSeen = now;
        return changed;
    }

    static class Parsed {
        String name;
        int type, tier;
        long count;
    }

    /** Split one visual row into troop entries: [name words][tier?][count]. */
    static List<Parsed> parseRow(String row) {
        List<Parsed> out = new ArrayList<>();
        List<String> nameWords = new ArrayList<>();
        int type = -1, tier = 0;
        long pending = -1;
        for (String raw : row.trim().split("\\s+")) {
            if (raw.isEmpty()) continue;
            String w = raw.replaceAll("^[^\\p{Alnum}]+|[^\\p{Alnum}.,]+$", "");
            if (w.isEmpty()) continue;

            int t = tierOf(w);
            if (t > 0) { tier = t; continue; }

            long n = countOf(w);
            if (n >= 0) {
                if (type >= 0) {
                    if (n >= 1) out.add(make(nameWords, type, tier, n));
                    nameWords.clear();
                    type = -1;
                    tier = 0;
                } else {
                    pending = n; // number shown before the name
                    nameWords.clear();
                }
                continue;
            }

            String lw = w.toLowerCase(Locale.US).replaceAll("[^a-z']", "");
            if (lw.isEmpty()) continue;
            int ty = typeOf(lw);
            if (ty >= 0 && type >= 0 && type != ty) {
                // a second troop name before any number: the first had its count in front
                if (pending >= 1) out.add(make(nameWords, type, tier, pending));
                nameWords.clear();
                tier = 0;
                pending = -1;
            }
            nameWords.add(lw);
            if (ty >= 0) type = ty;
        }
        if (type >= 0 && pending >= 1) out.add(make(nameWords, type, tier, pending));
        return out;
    }

    private static Parsed make(List<String> words, int type, int tier, long n) {
        Parsed p = new Parsed();
        p.name = String.join(" ", words);
        p.type = type;
        p.tier = tier;
        p.count = n;
        return p;
    }

    private boolean add(Parsed p) {
        Entry match = null;
        for (Entry e : entries) {
            if (e.type != p.type) continue;
            if (e.tier != 0 && p.tier != 0 && e.tier != p.tier) continue;
            if (similar(e.name, p.name)) { match = e; break; }
        }
        boolean isNew = match == null;
        if (isNew) {
            match = new Entry();
            match.name = p.name;
            match.type = p.type;
            entries.add(match);
        }
        boolean tierChanged = match.tier == 0 && p.tier != 0;
        if (tierChanged) match.tier = p.tier;
        long before = match.count();
        match.votes.merge(p.count, 1, Integer::sum);
        return isNew || tierChanged || match.count() != before;
    }

    static boolean similar(String a, String b) {
        if (a.equals(b)) return true;
        int max = Math.max(1, Math.max(a.length(), b.length()) / 8);
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

    static int typeOf(String lw) {
        for (int t = 0; t < 4; t++) {
            for (String k : EXACT[t]) if (lw.equals(k)) return t;
            for (String k : STARTS[t]) if (lw.startsWith(k)) return t;
        }
        return -1;
    }

    /** "XIV", "T14", "Lv14" -> 14; anything else -> 0. A lone "I" is ignored (too easy to misread). */
    static int tierOf(String w) {
        String u = w.toUpperCase(Locale.US);
        if (!u.equals("I") && ROMAN.contains(u)) return ROMAN_VAL.get(u);
        if (u.matches("T\\d{1,2}") || u.matches("LV\\.?\\d{1,2}")) {
            int v = Integer.parseInt(u.replaceAll("\\D", ""));
            return v >= 1 && v <= 16 ? v : 0;
        }
        return 0;
    }

    /** "1,735,813", "x120,000", "1.7M", "250K", "5000" -> value; not a number -> -1. */
    static long countOf(String w) {
        String s = w.replaceFirst("^[xX×]", "");
        try {
            if (s.matches("\\d{1,3}(,\\d{3})+") || s.matches("\\d{1,3}(\\.\\d{3}){2,}") || s.matches("\\d+"))
                return Long.parseLong(s.replaceAll("\\D", ""));
            if (s.matches("\\d+(\\.\\d+)?[kKmM]")) {
                double v = Double.parseDouble(s.substring(0, s.length() - 1));
                char u = Character.toLowerCase(s.charAt(s.length() - 1));
                return Math.round(v * (u == 'k' ? 1e3 : 1e6));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** JSON for the panel: [{"n":name,"t":type,"r":tier,"c":count}, ...] plus the session number. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"session\":").append(session).append(",\"e\":[");
        boolean first = true;
        for (Entry e : entries) {
            long c = e.count();
            if (c <= 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":\"").append(e.name.replaceAll("[\"\\\\]", "")).append("\",\"t\":\"")
                    .append(TYPE_NAMES[e.type]).append("\",\"r\":").append(e.tier)
                    .append(",\"c\":").append(c).append('}');
        }
        return sb.append("]}").toString();
    }
}
