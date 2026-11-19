package za.nully.l1l5gps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps recent ephemerides per satellite; serializable for offline reuse. */
public final class EphemerisStore {
    public static final double MAX_AGE_S = 6 * 3600;   // usable
    public static final double FRESH_S = 2 * 3600;     // nominal fit interval
    private final Map<Integer, List<Ephemeris>> map = new HashMap<>();

    public synchronized boolean add(Ephemeris e) {
        List<Ephemeris> l = map.computeIfAbsent(e.svid, k -> new ArrayList<>());
        for (Ephemeris x : l) if (x.week == e.week && x.toe == e.toe && x.iode == e.iode) return false;
        l.add(e);
        l.sort((a, b) -> Double.compare(b.week * 604800.0 + b.toe, a.week * 604800.0 + a.toe));
        while (l.size() > 4) l.remove(l.size() - 1);
        return true;
    }

    /** Age (s) of ephemeris relative to now; positive = ephemeris in the past. */
    public static double age(Ephemeris e, int week, double tow) {
        return (week - e.week) * 604800.0 + tow - e.toe;
    }

    public synchronized Ephemeris best(int svid, int week, double tow) {
        List<Ephemeris> l = map.get(svid);
        if (l == null) return null;
        Ephemeris bestE = null;
        double bestA = Double.MAX_VALUE;
        for (Ephemeris e : l) {
            double a = Math.abs(age(e, week, tow));
            if (a < bestA) { bestA = a; bestE = e; }
        }
        return bestA <= MAX_AGE_S ? bestE : null;
    }

    public synchronized int usableCount(int week, double tow) {
        int n = 0;
        for (int sv : map.keySet()) if (best(sv, week, tow) != null) n++;
        return n;
    }

    public synchronized String serialize() {
        StringBuilder b = new StringBuilder();
        for (List<Ephemeris> l : map.values()) for (Ephemeris e : l) b.append(e.toLine()).append('\n');
        return b.toString();
    }

    public synchronized void load(String s) {
        if (s == null) return;
        for (String line : s.split("\n")) {
            Ephemeris e = Ephemeris.fromLine(line);
            if (e != null) add(e);
        }
    }
}
