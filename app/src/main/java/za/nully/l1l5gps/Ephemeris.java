package za.nully.l1l5gps;

/** GPS LNAV broadcast ephemeris (decoded from L1 C/A subframes 1-3). */
public final class Ephemeris {
    public static final double GM = 3.986005e14;
    public static final double OMEGA_E = 7.2921151467e-5;
    public static final double F_REL = -4.442807633e-10;
    public static final double C = 299792458.0;

    public int svid, week, health, iodc, iode;
    public double tgd, toc, af0, af1, af2;
    public double crs, dn, m0, cuc, e, cus, sqrtA, toe;
    public double cic, omega0, cis, i0, crc, omega, omegaDot, idot;

    public static double wrap(double t) {
        if (t > 302400) t -= 604800;
        if (t < -302400) t += 604800;
        return t;
    }

    /** Satellite clock bias (s) at GPS time-of-week t, without relativistic term and TGD. */
    public double clockPoly(double t) {
        double dt = wrap(t - toc);
        return af0 + af1 * dt + af2 * dt * dt;
    }

    /** ECEF position (m) at time-of-week t, plus relativistic clock term (s) in [3]. */
    public double[] position(double t) {
        double A = sqrtA * sqrtA;
        double n = Math.sqrt(GM / (A * A * A)) + dn;
        double tk = wrap(t - toe);
        double M = m0 + n * tk;
        double E = M;
        for (int k = 0; k < 12; k++) E = M + e * Math.sin(E);
        double sinE = Math.sin(E), cosE = Math.cos(E);
        double v = Math.atan2(Math.sqrt(1 - e * e) * sinE, cosE - e);
        double phi = v + omega;
        double s2 = Math.sin(2 * phi), c2 = Math.cos(2 * phi);
        double u = phi + cus * s2 + cuc * c2;
        double r = A * (1 - e * cosE) + crs * s2 + crc * c2;
        double inc = i0 + cis * s2 + cic * c2 + idot * tk;
        double xp = r * Math.cos(u), yp = r * Math.sin(u);
        double om = omega0 + (omegaDot - OMEGA_E) * tk - OMEGA_E * toe;
        double co = Math.cos(om), so = Math.sin(om), ci = Math.cos(inc);
        return new double[]{
                xp * co - yp * ci * so,
                xp * so + yp * ci * co,
                yp * Math.sin(inc),
                F_REL * e * sqrtA * sinE
        };
    }

    public String toLine() {
        StringBuilder b = new StringBuilder();
        b.append(svid).append(',').append(week).append(',').append(health).append(',')
                .append(iodc).append(',').append(iode);
        double[] d = {tgd, toc, af0, af1, af2, crs, dn, m0, cuc, e, cus, sqrtA, toe,
                cic, omega0, cis, i0, crc, omega, omegaDot, idot};
        for (double x : d) b.append(',').append(x);
        return b.toString();
    }

    public static Ephemeris fromLine(String line) {
        try {
            String[] p = line.trim().split(",");
            if (p.length != 26) return null;
            Ephemeris q = new Ephemeris();
            q.svid = Integer.parseInt(p[0]); q.week = Integer.parseInt(p[1]);
            q.health = Integer.parseInt(p[2]); q.iodc = Integer.parseInt(p[3]); q.iode = Integer.parseInt(p[4]);
            double[] d = new double[21];
            for (int i = 0; i < 21; i++) d[i] = Double.parseDouble(p[5 + i]);
            q.tgd = d[0]; q.toc = d[1]; q.af0 = d[2]; q.af1 = d[3]; q.af2 = d[4];
            q.crs = d[5]; q.dn = d[6]; q.m0 = d[7]; q.cuc = d[8]; q.e = d[9]; q.cus = d[10];
            q.sqrtA = d[11]; q.toe = d[12]; q.cic = d[13]; q.omega0 = d[14]; q.cis = d[15];
            q.i0 = d[16]; q.crc = d[17]; q.omega = d[18]; q.omegaDot = d[19]; q.idot = d[20];
            return q;
        } catch (Exception ex) {
            return null;
        }
    }
}
