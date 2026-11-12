package com.harley.marchhelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a battle report's "Troop Buff" tab while it is scrolled.
 *
 * Layout: rows "Attack+852%  [icon]  Attack+3022%" — left column is the report owner (you), right is the
 * other side. Each troop type is a group of three rows (Attack, Defense, HP). Sections in order:
 * "Buff for allied troops" (+, Ground/Ranged/Mounted/Siege), "Debuff against enemy troops" (−, same order),
 * "Trap" (Attack only: Trap, Rock, Abatis, Fire Arrow).
 * Troop type comes from the icon label when it is read, otherwise from the section order.
 * Also reads the title (Attacks Won / Defenses Lost …), date, both players' names and the generals' names.
 */
public class ReportScan {

    static final Pattern STAT = Pattern.compile("(attack|defen[cs]e|hp)\\s*([+-])\\s*(\\d{1,5}(?:\\.\\d+)?)\\s*%", Pattern.CASE_INSENSITIVE);
    static final Pattern DATE = Pattern.compile("(20\\d\\d-\\d\\d-\\d\\d \\d\\d:\\d\\d(?::\\d\\d)?)");
    static final Pattern TITLE = Pattern.compile("(attacks?|defen[cs]es?|rall(?:y|ies)|reinforce\\w*)\\s+(won|lost)", Pattern.CASE_INSENSITIVE);
    static final Pattern NAME = Pattern.compile("\\[\\s*([A-Za-z0-9]{2,5})\\s*\\]\\s*([^\\[\\]]{2,24})");
    static final Pattern JUNK = Pattern.compile("(?i)survived|killed|captured|officer|none|power|status|level|star|wounded|dead");
    static final String[] TKEY = {"ground", "ranged", "mounted", "siege"};

    static class Row { String stat; int y; Double left, right; boolean neg; }
    static class Group { List<Row> rows = new ArrayList<>(); int troop = -1; boolean neg; boolean trap; int top() { return rows.get(0).y; } int bot() { return rows.get(rows.size() - 1).y; } }

    /** One frame → {side → kind → troop → stat → value}, plus meta. */
    public static class Read {
        public final Map<String, Double> vals = new LinkedHashMap<>();   // "L.buff.ground.attack" → 852
        public String date, title, me, enemy;
        public final List<String> genL = new ArrayList<>(), genR = new ArrayList<>();
    }

    static int cy(ScoutTally.W w) { return (w.top + w.bot) / 2; }

    public static Read extract(List<ScoutTally.W> ws, int screenW) {
        Read out = new Read();
        if (ws.isEmpty()) return out;
        List<Integer> hs = new ArrayList<>();
        for (ScoutTally.W w : ws) hs.add(w.bot - w.top);
        hs.sort(Integer::compare);
        int h = Math.max(8, hs.get(hs.size() / 2));
        int mid = screenW / 2;

        // --- visual lines per half (so "Attack" "+852%" split into two words still reads)
        List<ScoutTally.W> lines = lines(ws, h);
        StringBuilder allText = new StringBuilder();
        for (ScoutTally.W l : lines) allText.append(l.t).append('\n');
        String all = allText.toString();
        Matcher m = DATE.matcher(all); if (m.find()) out.date = m.group(1);
        m = TITLE.matcher(all); if (m.find()) out.title = (m.group(1) + " " + m.group(2)).toLowerCase(Locale.US);
        for (ScoutTally.W l : lines) {
            Matcher n = NAME.matcher(l.t);
            while (n.find()) {
                String nm = "[" + n.group(1) + "]" + n.group(2).replaceAll("\\s+[XxY]\\s*:.*$", "").trim();
                if ((l.l + l.r) / 2 < mid) { if (out.me == null) out.me = nm; } else if (out.enemy == null) out.enemy = nm;
            }
        }

        // --- stat rows
        List<Row> rows = new ArrayList<>();
        for (ScoutTally.W l : lines) {
            Matcher s = STAT.matcher(l.t.replace(" ", ""));
            if (!s.find()) continue;
            String stat = s.group(1).toLowerCase(Locale.US).startsWith("defen") ? "defense" : s.group(1).toLowerCase(Locale.US);
            double v = Double.parseDouble(s.group(3));
            boolean left = (l.l + l.r) / 2 < mid;
            Row r = null;
            for (Row x : rows) if (x.stat.equals(stat) && Math.abs(x.y - cy(l)) < h) { r = x; break; }
            if (r == null) { r = new Row(); r.stat = stat; r.y = cy(l); r.neg = s.group(2).equals("-"); rows.add(r); }
            if (left) r.left = v; else r.right = v;
        }
        rows.sort((a, b) -> Integer.compare(a.y, b.y));
        if (rows.isEmpty()) { readGenerals(ws, lines, h, mid, out); return out; }

        // typical row spacing inside a group
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) gaps.add(rows.get(i).y - rows.get(i - 1).y);
        gaps.sort(Integer::compare);
        int step = gaps.isEmpty() ? 2 * h : Math.max(h, gaps.get(0));

        // --- groups: a new group starts at every Attack row, a sign change, or a big gap
        List<Group> groups = new ArrayList<>();
        Group g = null;
        for (Row r : rows) {
            boolean split = g == null || r.stat.equals("attack") || r.neg != g.neg || r.y - g.bot() > step * 1.6;
            if (split) { g = new Group(); g.neg = r.neg; groups.add(g); }
            g.rows.add(r);
        }
        // trap groups: attack-only groups that sit next to another attack-only group
        for (int i = 0; i < groups.size(); i++) {
            Group a = groups.get(i);
            if (a.neg || a.rows.size() != 1 || !a.rows.get(0).stat.equals("attack")) continue;
            boolean prev = i > 0 && groups.get(i - 1).rows.size() == 1 && !groups.get(i - 1).neg;
            boolean next = i + 1 < groups.size() && groups.get(i + 1).rows.size() == 1 && !groups.get(i + 1).neg;
            if (prev || next) a.trap = true;
        }

        // --- section headers and icon labels
        int buffHdr = Integer.MIN_VALUE, debuffHdr = Integer.MIN_VALUE, trapHdr = Integer.MIN_VALUE;
        List<int[]> labels = new ArrayList<>();   // {y, troopIndex}
        for (ScoutTally.W l : lines) {
            String t = l.t.toLowerCase(Locale.US);
            if (t.contains("allied")) buffHdr = cy(l);
            if (t.contains("against enemy") || t.startsWith("debuff")) debuffHdr = cy(l);
            boolean centred = Math.abs((l.l + l.r) / 2 - mid) < screenW * 0.12;
            if (centred && t.trim().equals("trap") && l.r - l.l > h * 2) trapHdr = Math.max(trapHdr, cy(l));
        }
        for (ScoutTally.W w : ws) {
            if (Math.abs((w.l + w.r) / 2 - mid) > screenW * 0.12) continue;
            String t = w.t.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
            int idx = t.startsWith("ground") ? 0 : t.startsWith("ranged") ? 1 : t.startsWith("mounted") ? 2 : t.startsWith("siege") ? 3 : -1;
            if (idx >= 0) labels.add(new int[]{cy(w), idx});
        }
        // label → the group whose rows it sits beside (label is drawn at the bottom of the icon)
        for (int[] lb : labels)
            for (Group x : groups)
                if (!x.trap && lb[0] >= x.top() - step && lb[0] <= x.bot() + step * 1.2) { x.troop = lb[1]; break; }
        // header → first group below it in that section is Ground
        for (Group x : groups) {
            if (x.trap || x.troop >= 0) continue;
            int hdr = x.neg ? debuffHdr : buffHdr;
            if (hdr == Integer.MIN_VALUE || hdr > x.top()) continue;
            boolean first = true;
            for (Group y : groups) if (y != x && !y.trap && y.neg == x.neg && y.top() > hdr && y.top() < x.top()) first = false;
            if (first) x.troop = 0;
        }
        // propagate along runs of same-sign groups
        for (int pass = 0; pass < 4; pass++)
            for (int i = 0; i < groups.size(); i++) {
                Group x = groups.get(i);
                if (x.trap || x.troop >= 0) continue;
                if (i > 0) { Group p = groups.get(i - 1); if (!p.trap && p.neg == x.neg && p.troop >= 0 && p.troop < 3) x.troop = p.troop + 1; }
                if (x.troop < 0 && i + 1 < groups.size()) { Group n = groups.get(i + 1); if (!n.trap && n.neg == x.neg && n.troop > 0) x.troop = n.troop - 1; }
            }
        int trapIdx = 0;
        for (Group x : groups) {
            if (x.trap) {
                Row r = x.rows.get(0);
                // trap rows: index from the "Trap" header when visible
                int k = trapHdr != Integer.MIN_VALUE && r.y > trapHdr ? trapIdx++ : -1;
                if (r.left != null) out.vals.put("L.trap." + (k >= 0 ? k : "any") + ".attack", r.left);
                if (r.right != null) out.vals.put("R.trap." + (k >= 0 ? k : "any") + ".attack", r.right);
                continue;
            }
            if (x.troop < 0) continue;
            String kind = x.neg ? "debuff" : "buff";
            for (Row r : x.rows) {
                if (r.left != null) out.vals.put("L." + kind + "." + TKEY[x.troop] + "." + r.stat, r.left);
                if (r.right != null) out.vals.put("R." + kind + "." + TKEY[x.troop] + "." + r.stat, r.right);
            }
        }
        readGenerals(ws, lines, h, mid, out);
        return out;
    }

    /** "Main General" / "Assistant General" sections: names under the portraits, left and right. */
    static void readGenerals(List<ScoutTally.W> ws, List<ScoutTally.W> lines, int h, int mid, Read out) {
        for (ScoutTally.W hdr : lines) {
            String t = hdr.t.toLowerCase(Locale.US);
            if (!(t.contains("main general") || t.contains("assistant general"))) continue;
            int top = hdr.bot, bot = top + h * 9;
            for (ScoutTally.W l : lines)
                if (l.top > top && l.t.toLowerCase(Locale.US).contains("level")) { bot = Math.min(bot, l.top); break; }
            StringBuilder L = new StringBuilder(), R = new StringBuilder();
            for (ScoutTally.W w : ws) {
                if (w.top <= top || w.bot > bot + h) continue;
                if (!w.t.matches(".*[A-Za-z]{2,}.*") || JUNK.matcher(w.t).find()) continue;
                int c = (w.l + w.r) / 2;
                if (c < mid - h * 3) L.append(w.t).append(' '); else if (c > mid + h * 3) R.append(w.t).append(' ');
            }
            if (L.length() > 0) out.genL.add(L.toString().trim());
            if (R.length() > 0) out.genR.add(R.toString().trim());
        }
    }

    /** Join words into visual lines, split at the screen centre so the two columns stay apart. */
    static List<ScoutTally.W> lines(List<ScoutTally.W> ws, int h) {
        List<ScoutTally.W> sorted = new ArrayList<>(ws);
        sorted.sort((a, b) -> Integer.compare(a.l, b.l));
        List<List<ScoutTally.W>> rows = new ArrayList<>();
        for (ScoutTally.W w : sorted) {
            List<ScoutTally.W> hit = null;
            for (List<ScoutTally.W> r : rows) {
                ScoutTally.W last = r.get(r.size() - 1);
                if (Math.abs(cy(last) - cy(w)) < h * 0.6 && w.l - last.r < h * 2.2) { hit = r; break; }
            }
            if (hit == null) { hit = new ArrayList<>(); rows.add(hit); }
            hit.add(w);
        }
        List<ScoutTally.W> out = new ArrayList<>();
        for (List<ScoutTally.W> r : rows) {
            StringBuilder sb = new StringBuilder();
            int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, rr = 0, b = 0;
            for (ScoutTally.W w : r) { sb.append(w.t).append(' '); l = Math.min(l, w.l); t = Math.min(t, w.top); rr = Math.max(rr, w.r); b = Math.max(b, w.bot); }
            out.add(new ScoutTally.W(sb.toString().trim(), l, t, rr, b));
        }
        return out;
    }

    // ---------- voting across frames, one report at a time ----------
    private final Map<String, Map<Double, Integer>> votes = new HashMap<>();
    private final Map<String, Map<String, Integer>> meta = new HashMap<>();
    private String session = null;

    public void reset() { votes.clear(); meta.clear(); session = null; }

    public boolean feed(Read r) {
        // a different report date means a new report
        if (r.date != null && session != null && !r.date.equals(session)) reset();
        if (r.date != null) session = r.date;
        String before = toJson();
        for (Map.Entry<String, Double> e : r.vals.entrySet())
            votes.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(e.getValue(), 1, Integer::sum);
        vote("date", r.date); vote("title", r.title); vote("me", r.me); vote("enemy", r.enemy);
        for (int i = 0; i < r.genL.size(); i++) vote("genL" + i, r.genL.get(i));
        for (int i = 0; i < r.genR.size(); i++) vote("genR" + i, r.genR.get(i));
        return !before.equals(toJson());
    }

    private void vote(String k, String v) { if (v != null && !v.isEmpty()) meta.computeIfAbsent(k, x -> new HashMap<>()).merge(v, 1, Integer::sum); }

    private String top(String k) {
        Map<String, Integer> m = meta.get(k); if (m == null) return null;
        String b = null; int n = -1;
        for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() > n) { n = e.getValue(); b = e.getKey(); }
        return b;
    }

    static String q(String s) { return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"v\":{");
        boolean first = true;
        for (Map.Entry<String, Map<Double, Integer>> e : new java.util.TreeMap<>(votes).entrySet()) {
            double bv = 0; int bn = -1;
            for (Map.Entry<Double, Integer> c : e.getValue().entrySet()) if (c.getValue() > bn) { bn = c.getValue(); bv = c.getKey(); }
            if (!first) sb.append(',');
            first = false;
            sb.append(q(e.getKey())).append(':').append(bv == Math.rint(bv) ? String.valueOf((long) bv) : String.valueOf(bv));
        }
        sb.append("},\"date\":").append(q(top("date"))).append(",\"title\":").append(q(top("title")))
          .append(",\"me\":").append(q(top("me"))).append(",\"enemy\":").append(q(top("enemy")))
          .append(",\"genL\":[").append(q(top("genL0"))).append(',').append(q(top("genL1"))).append("]")
          .append(",\"genR\":[").append(q(top("genR0"))).append(',').append(q(top("genR1"))).append("]}");
        return sb.toString();
    }
}
