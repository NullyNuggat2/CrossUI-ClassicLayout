package za.nully.l1l5gps;

import java.util.HashMap;
import java.util.Map;

/**
 * Decodes GPS L1 C/A LNAV subframes 1-3 (from Android GnssNavigationMessage)
 * into an Ephemeris, so the app works fully offline.
 */
public final class LnavDecoder {
    private static final double PI = 3.1415926535898; // value defined by IS-GPS-200
    private final Map<Integer, int[][]> pending = new HashMap<>();

    private static final int[][] P = {
            {1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23},
            {2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24},
            {1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22},
            {2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23},
            {1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24},
            {3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24}};
    private static final int[] S = {0, 1, 0, 1, 1, 0}; // 0 -> D29*, 1 -> D30*

    static int parity(int d, int d29, int d30) {
        int r = 0;
        for (int k = 0; k < 6; k++) {
            int p = S[k] == 0 ? d29 : d30;
            for (int j : P[k]) p ^= (d >> (24 - j)) & 1;
            r = (r << 1) | p;
        }
        return r;
    }

    /** Returns 10 data words (24 bits each), or null if invalid. */
    static int[] extract(byte[] data) {
        if (data == null || data.length < 40) return null;
        int[] raw = new int[10];
        for (int i = 0; i < 10; i++) {
            raw[i] = (((data[4 * i] & 0xFF) << 24) | ((data[4 * i + 1] & 0xFF) << 16)
                    | ((data[4 * i + 2] & 0xFF) << 8) | (data[4 * i + 3] & 0xFF)) & 0x3FFFFFFF;
        }
        // Mode A: raw bits as transmitted, verify parity and undo D30* inversion.
        int[] out = new int[10];
        int prev = 0;
        boolean ok = true;
        for (int i = 0; i < 10; i++) {
            int w = raw[i];
            int d30s = prev & 1, d29s = (prev >> 1) & 1;
            int d = (w >> 6) & 0xFFFFFF;
            if (d30s == 1) d ^= 0xFFFFFF;
            if (parity(d, d29s, d30s) != (w & 0x3F)) { ok = false; break; }
            out[i] = d;
            prev = w & 3;
        }
        if (!ok) {
            // Mode B: chipset already corrected the bits.
            for (int i = 0; i < 10; i++) out[i] = (raw[i] >> 6) & 0xFFFFFF;
        }
        if ((out[0] >> 16) != 0x8B) return null; // TLM preamble
        return out;
    }

    private static long u(int w, int a, int len) {
        return ((long) (w >> (24 - (a + len - 1)))) & ((1L << len) - 1);
    }

    private static long s(long v, int len) {
        return (v & (1L << (len - 1))) != 0 ? v - (1L << len) : v;
    }

    private static long cat(int wa, int wb) {
        return (u(wa, 17, 8) << 24) | u(wb, 1, 24);
    }

    private static double sc(double v, int exp) {
        return Math.scalb(v, exp);
    }

    /** Feed one subframe. Returns a complete ephemeris when subframes 1,2,3 match. */
    public Ephemeris onMessage(int svid, byte[] data, int rxWeek) {
        int[] w = extract(data);
        if (w == null) return null;
        int id = (int) u(w[1], 20, 3);
        if (id < 1 || id > 3) return null;
        int[][] set = pending.get(svid);
        if (set == null) { set = new int[3][]; pending.put(svid, set); }
        set[id - 1] = w;
        if (set[0] == null || set[1] == null || set[2] == null) return null;

        int[] f1 = set[0], f2 = set[1], f3 = set[2];
        Ephemeris q = new Ephemeris();
        q.svid = svid;
        int wn10 = (int) u(f1[2], 1, 10);
        q.week = rxWeek > 0 ? wn10 + 1024 * (int) Math.round((rxWeek - wn10) / 1024.0) : wn10;
        q.health = (int) u(f1[2], 17, 6);
        q.iodc = (int) ((u(f1[2], 23, 2) << 8) | u(f1[7], 1, 8));
        q.tgd = sc(s(u(f1[6], 17, 8), 8), -31);
        q.toc = u(f1[7], 9, 16) * 16.0;
        q.af2 = sc(s(u(f1[8], 1, 8), 8), -55);
        q.af1 = sc(s(u(f1[8], 9, 16), 16), -43);
        q.af0 = sc(s(u(f1[9], 1, 22), 22), -31);

        int iode2 = (int) u(f2[2], 1, 8);
        q.crs = sc(s(u(f2[2], 9, 16), 16), -5);
        q.dn = sc(s(u(f2[3], 1, 16), 16), -43) * PI;
        q.m0 = sc(s(cat(f2[3], f2[4]), 32), -31) * PI;
        q.cuc = sc(s(u(f2[5], 1, 16), 16), -29);
        q.e = sc(cat(f2[5], f2[6]), -33);
        q.cus = sc(s(u(f2[7], 1, 16), 16), -29);
        q.sqrtA = sc(cat(f2[7], f2[8]), -19);
        q.toe = u(f2[9], 1, 16) * 16.0;

        q.cic = sc(s(u(f3[2], 1, 16), 16), -29);
        q.omega0 = sc(s(cat(f3[2], f3[3]), 32), -31) * PI;
        q.cis = sc(s(u(f3[4], 1, 16), 16), -29);
        q.i0 = sc(s(cat(f3[4], f3[5]), 32), -31) * PI;
        q.crc = sc(s(u(f3[6], 1, 16), 16), -5);
        q.omega = sc(s(cat(f3[6], f3[7]), 32), -31) * PI;
        q.omegaDot = sc(s(u(f3[8], 1, 24), 24), -43) * PI;
        int iode3 = (int) u(f3[9], 1, 8);
        q.idot = sc(s(u(f3[9], 9, 14), 14), -43) * PI;
        q.iode = iode2;

        if (iode2 != iode3 || iode2 != (q.iodc & 0xFF)) return null; // mid-update, wait
        if (q.sqrtA < 5000 || q.sqrtA > 5300 || q.e > 0.05) return null; // sanity
        return q;
    }
}
