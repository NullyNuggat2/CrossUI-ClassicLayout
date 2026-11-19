package za.nully.l1l5gps;

import java.util.ArrayList;
import java.util.List;

/** Weighted least-squares GPS position solver. */
public final class Solver {
    public static final double C = Ephemeris.C;
    public static final double F_L1 = 1575.42e6, F_L5 = 1176.45e6;
    /** Iono-free combination coefficients: P_IF = A*P1 - B*P5. */
    public static final double IF_A = F_L1 * F_L1 / (F_L1 * F_L1 - F_L5 * F_L5);
    public static final double IF_B = F_L5 * F_L5 / (F_L1 * F_L1 - F_L5 * F_L5);
    private static final double MASK = Math.sin(Math.toRadians(10));

    public static final class Obs {
        public final Ephemeris eph; public final double pr, tTx, sigma; public final boolean useTgd;
        public Obs(Ephemeris eph, double pr, double tTx, double sigma, boolean useTgd) {
            this.eph = eph; this.pr = pr; this.tTx = tTx; this.sigma = sigma; this.useTgd = useTgd;
        }
    }

    public static final class Solution {
        public double[] x;              // ECEF x,y,z (m), clock bias (m)
        public double lat, lon, alt;    // deg, deg, m
        public double hdop, rms, acc;   // acc = estimated horizontal 1-sigma (m)
        public int used;
    }

    public static Solution solve(List<Obs> obs, double[] seed) {
        if (obs.size() < 4) return null;
        List<double[]> sats = new ArrayList<>();
        for (Obs o : obs) {
            double t = o.tTx - o.eph.clockPoly(o.tTx);
            double[] p = o.eph.position(t);
            double dts = o.eph.clockPoly(t) + p[3];
            if (o.useTgd) dts -= o.eph.tgd;
            sats.add(new double[]{p[0], p[1], p[2], o.pr + C * dts, o.sigma});
        }
        // Simple fault exclusion: drop worst outlier up to 3 times.
        for (int round = 0; round < 4; round++) {
            double[] res = new double[sats.size()];
            Solution s = wls(sats, seed, res);
            if (s == null) return null;
            int worst = -1; double wv = 0;
            for (int i = 0; i < res.length; i++)
                if (!Double.isNaN(res[i]) && Math.abs(res[i]) > wv) { wv = Math.abs(res[i]); worst = i; }
            double[] ab = new double[s.used]; int k = 0;
            for (double r : res) if (!Double.isNaN(r) && k < ab.length) ab[k++] = Math.abs(r);
            java.util.Arrays.sort(ab, 0, k);
            double med = k > 0 ? ab[k / 2] : 0;
            double lim = Math.max(30.0, 6 * med);
            if (s.used >= 6 && wv > lim && round < 3) { sats.remove(worst); continue; }
            return s;
        }
        return null;
    }

    private static Solution wls(List<double[]> sats, double[] seed, double[] resOut) {
        double[] x = seed != null && seed.length == 4 ? seed.clone() : new double[4];
        for (int iter = 0; iter < 20; iter++) {
            boolean known = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]) > 6.0e6;
            double[] up = null;
            if (known) {
                double[] lla = ecefToLla(x[0], x[1], x[2]);
                double la = Math.toRadians(lla[0]), lo = Math.toRadians(lla[1]);
                up = new double[]{Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la)};
            }
            double[][] N = new double[4][4];
            double[][] Nu = new double[4][4];
            double[] bvec = new double[4];
            int n = 0;
            for (int i = 0; i < sats.size(); i++) {
                double[] s = sats.get(i);
                resOut[i] = Double.NaN;
                double rho = dist(s[0] - x[0], s[1] - x[1], s[2] - x[2]);
                double th = Ephemeris.OMEGA_E * rho / C; // Sagnac / Earth rotation during flight
                double xs = Math.cos(th) * s[0] + Math.sin(th) * s[1];
                double ys = -Math.sin(th) * s[0] + Math.cos(th) * s[1];
                double dx = xs - x[0], dy = ys - x[1], dz = s[2] - x[2];
                rho = dist(dx, dy, dz);
                double sinEl = 1, trop = 0;
                if (up != null) {
                    sinEl = (dx * up[0] + dy * up[1] + dz * up[2]) / rho;
                    if (sinEl < MASK) continue;
                    trop = 2.47 / (sinEl + 0.0121);
                }
                double[] h = {-dx / rho, -dy / rho, -dz / rho, 1};
                double r = s[3] - rho - x[3] - trop;
                double w = sinEl * sinEl / (s[4] * s[4]);
                resOut[i] = r;
                for (int a = 0; a < 4; a++) {
                    bvec[a] += w * h[a] * r;
                    for (int c = 0; c < 4; c++) { N[a][c] += w * h[a] * h[c]; Nu[a][c] += h[a] * h[c]; }
                }
                n++;
            }
            if (n < 4) return null;
            double[][] Ni = inv(N);
            if (Ni == null) return null;
            double[] d = new double[4];
            for (int a = 0; a < 4; a++) for (int c = 0; c < 4; c++) d[a] += Ni[a][c] * bvec[c];
            for (int a = 0; a < 4; a++) x[a] += d[a];
            if (known && dist(d[0], d[1], d[2]) < 1e-3) {
                Solution sol = new Solution();
                sol.x = x;
                double[] lla = ecefToLla(x[0], x[1], x[2]);
                sol.lat = lla[0]; sol.lon = lla[1]; sol.alt = lla[2];
                sol.used = n;
                double ss = 0;
                for (int i = 0; i < resOut.length; i++) if (!Double.isNaN(resOut[i])) ss += resOut[i] * resOut[i];
                sol.rms = n > 4 ? Math.sqrt(ss / (n - 4)) : Double.NaN;
                double[][] Q = inv(Nu);
                sol.hdop = Q == null ? Double.NaN : hdop(Q, lla[0], lla[1]);
                double sig = Double.isNaN(sol.rms) ? 5.0 : Math.max(sol.rms, 2.0);
                sol.acc = sol.hdop * sig;
                return sol;
            }
        }
        return null;
    }

    private static double hdop(double[][] Q, double latDeg, double lonDeg) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        double[] e = {-Math.sin(lo), Math.cos(lo), 0};
        double[] nn = {-Math.sin(la) * Math.cos(lo), -Math.sin(la) * Math.sin(lo), Math.cos(la)};
        return Math.sqrt(quad(Q, e) + quad(Q, nn));
    }

    private static double quad(double[][] Q, double[] v) {
        double s = 0;
        for (int a = 0; a < 3; a++) for (int c = 0; c < 3; c++) s += v[a] * Q[a][c] * v[c];
        return s;
    }

    static double dist(double a, double b, double c) { return Math.sqrt(a * a + b * b + c * c); }

    static double[][] inv(double[][] m) {
        int n = m.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) { System.arraycopy(m[i], 0, a[i], 0, n); a[i][n + i] = 1; }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            if (Math.abs(a[p][c]) < 1e-15) return null;
            double[] t = a[c]; a[c] = a[p]; a[p] = t;
            double pv = a[c][c];
            for (int k = 0; k < 2 * n; k++) a[c][k] /= pv;
            for (int r = 0; r < n; r++) if (r != c) {
                double f = a[r][c];
                if (f != 0) for (int k = 0; k < 2 * n; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[][] o = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], n, o[i], 0, n);
        return o;
    }

    /** WGS84 ECEF -> {lat deg, lon deg, height m}. */
    public static double[] ecefToLla(double x, double y, double z) {
        double a = 6378137.0, f = 1 / 298.257223563, e2 = f * (2 - f);
        double lon = Math.atan2(y, x), p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1 - e2)), h = 0;
        for (int i = 0; i < 6; i++) {
            double sl = Math.sin(lat);
            double N = a / Math.sqrt(1 - e2 * sl * sl);
            h = p / Math.cos(lat) - N;
            lat = Math.atan2(z, p * (1 - e2 * N / (N + h)));
        }
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), h};
    }

    public static double[] llaToEcef(double latDeg, double lonDeg, double h) {
        double a = 6378137.0, f = 1 / 298.257223563, e2 = f * (2 - f);
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        double N = a / Math.sqrt(1 - e2 * Math.sin(la) * Math.sin(la));
        return new double[]{(N + h) * Math.cos(la) * Math.cos(lo), (N + h) * Math.cos(la) * Math.sin(lo),
                (N * (1 - e2) + h) * Math.sin(la)};
    }
}
