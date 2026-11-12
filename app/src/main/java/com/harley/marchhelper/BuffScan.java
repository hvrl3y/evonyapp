package com.harley.marchhelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the Monarch Detail screen (Military tab) while it is scrolled.
 *
 * Each row is a label on the left ("Attacking Ground Troop Attack", may wrap onto two lines) and a value
 * on the right ("+239.7%", "+360", "1,927,850"). Rows are matched to known keys:
 *   ground|ranged|mounted|siege . basic|attacking|incity|reinforcing|single . attack|defense|hp
 *   march, rally, reinforce, slots
 * Values are voted across frames, so a single misread frame doesn't stick.
 */
public class BuffScan {

    static final Pattern VAL = Pattern.compile("^[+-]?\\d[\\d,]*(\\.\\d+)?%?$");
    static final String[] TROOP = {"ground troop", "ranged troop", "mounted troop", "siege machine"};
    static final String[] TKEY = {"ground", "ranged", "mounted", "siege"};
    static final String[] STAT = {"attack", "defense", "hp"};
    static final String[][] CTX = {{"attacking ", "attacking"}, {"in city ", "incity"}, {"reinforcing ", "reinforcing"}, {"single ", "single"}};
    static final String[][] OTHER = {{"march size", "march"}, {"rally capacity", "rally"}, {"reinforcement capacity", "reinforce"}, {"march slots", "slots"}};

    /** Label text (lower case, letters and single spaces only) to key, or null. */
    static String keyFor(String label) {
        String s = label.toLowerCase(Locale.US).replaceAll("[^a-z]+", " ").replaceAll("\\s+", " ").trim();
        s = s.replace("incity", "in city").replace("in clty", "in city").replace("defence", "defense");
        for (String[] o : OTHER) if (s.endsWith(o[0])) return o[1];
        for (int t = 0; t < TROOP.length; t++)
            for (String st : STAT) {
                String tail = TROOP[t] + " " + st;
                if (!s.endsWith(tail)) continue;
                String head = s.substring(0, s.length() - tail.length());
                for (String[] c : CTX) if (head.endsWith(c[0])) return TKEY[t] + "." + c[1] + "." + st;
                // anything else in front (a section header in the same band) still means the basic buff,
                // unless it's a context word we don't track (e.g. "defending")
                if (head.endsWith("defending ") || head.endsWith("against monsters ")) return null;
                return TKEY[t] + ".basic." + st;
            }
        return null;
    }

    static double parseVal(String v) {
        try { return Double.parseDouble(v.replace("%", "").replace("+", "").replace(",", "")); }
        catch (Exception e) { return Double.NaN; }
    }

    /** One frame: label/value pairs found on screen. */
    public static Map<String, Double> extract(List<ScoutTally.W> ws, int screenW) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (ws.isEmpty()) return out;
        // median word height
        List<Integer> hs = new ArrayList<>();
        for (ScoutTally.W w : ws) hs.add(w.bot - w.top);
        hs.sort(Integer::compare);
        int h = Math.max(8, hs.get(hs.size() / 2));

        // value cells: right-hand tokens that look like numbers; join a lone "+" with the number after it
        List<ScoutTally.W> vals = new ArrayList<>();
        for (ScoutTally.W w : ws) {
            if (w.l < screenW * 0.42) continue;
            String t = w.t.replace('O', '0').replace('o', '0').replace(" ", "");
            if (VAL.matcher(t).matches()) vals.add(new ScoutTally.W(t, w.l, w.top, w.r, w.bot));
        }
        for (ScoutTally.W w : ws) {
            if (!w.t.equals("+") || w.l < screenW * 0.42) continue;
            for (int i = 0; i < vals.size(); i++) {
                ScoutTally.W v = vals.get(i);
                if (Math.abs(cy(v) - cy(w)) < h / 2 && v.l > w.l && v.l - w.r < h)
                    vals.set(i, new ScoutTally.W("+" + v.t, w.l, v.top, v.r, v.bot));
            }
        }
        vals.sort((a, b) -> Integer.compare(cy(a), cy(b)));
        for (int i = 0; i < vals.size(); i++) {
            ScoutTally.W v = vals.get(i);
            int c = cy(v);
            double lo = c - 1.7 * h, hi = c + 1.7 * h;
            if (i > 0) lo = Math.max(lo, (cy(vals.get(i - 1)) + c) / 2.0);
            if (i + 1 < vals.size()) hi = Math.min(hi, (cy(vals.get(i + 1)) + c) / 2.0);
            List<ScoutTally.W> lab = new ArrayList<>();
            for (ScoutTally.W w : ws) {
                if (w.r > v.l - h / 3 || w == v) continue;  // label sits left of its own value
                int wc = cy(w);
                if (wc >= lo && wc < hi) lab.add(w);
            }
            if (lab.isEmpty()) continue;
            lab.sort((a, b) -> Math.abs(cy(a) - cy(b)) < h / 2 ? Integer.compare(a.l, b.l) : Integer.compare(cy(a), cy(b)));
            StringBuilder sb = new StringBuilder();
            for (ScoutTally.W w : lab) sb.append(w.t).append(' ');
            String key = keyFor(sb.toString());
            if (key == null) continue;
            double val = parseVal(v.t);
            if (Double.isNaN(val)) continue;
            // a percent row needs a percent value, a flat/count row must not have one
            boolean pct = v.t.endsWith("%");
            boolean wantPct = key.contains(".basic.") || key.contains(".attacking.") || key.contains(".incity.") || key.contains(".reinforcing.");
            if (pct != wantPct) continue;
            out.put(key, val);
        }
        return out;
    }

    static int cy(ScoutTally.W w) { return (w.top + w.bot) / 2; }

    // ---------- voting across frames ----------
    private final Map<String, Map<Double, Integer>> votes = new HashMap<>();

    public void reset() { votes.clear(); }

    /** Returns true when the best value of any key changed. */
    public boolean feed(Map<String, Double> frame) {
        Map<String, Double> before = best();
        for (Map.Entry<String, Double> e : frame.entrySet())
            votes.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(e.getValue(), 1, Integer::sum);
        return !before.equals(best());
    }

    public Map<String, Double> best() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Double, Integer>> e : votes.entrySet()) {
            double bv = 0; int bn = -1;
            for (Map.Entry<Double, Integer> c : e.getValue().entrySet())
                if (c.getValue() > bn) { bn = c.getValue(); bv = c.getKey(); }
            out.put(e.getKey(), bv);
        }
        return out;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"v\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : best().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            double d = e.getValue();
            sb.append('"').append(e.getKey()).append("\":").append(d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d));
        }
        return sb.append("}}").toString();
    }
}
