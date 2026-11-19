package za.nully.l1l5gps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Emergency GPS: positions using only GPS L1 C/A and L5 raw measurements. */
public class MainActivity extends Activity {
    private static final long WEEK_NS = 604800L * 1_000_000_000L;

    private LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EphemerisStore store = new EphemerisStore();
    private final LnavDecoder decoder = new LnavDecoder();
    private SharedPreferences prefs;
    private boolean running;
    private int rxWeek = -1;
    private double rxTow;
    private double[] seed;
    private Solver.Solution fix;
    private String fixMode = "";
    private long fixTime;
    private final ArrayDeque<double[]> avg = new ArrayDeque<>();
    private Location phoneLoc;
    private String measStatus = "starting", navStatus = "starting";
    private int nL1, nL5, nEph;
    private long lastSave;

    private TextView tvStatus, tvCoords, tvAcc, tvAvg, tvSats, tvPhone;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        prefs = getSharedPreferences("eph", MODE_PRIVATE);
        store.load(prefs.getString("store", ""));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(40, 60, 40, 40);

        TextView title = tv(22, Color.rgb(255, 80, 80), true);
        title.setText("EMERGENCY GPS  ·  L1 C/A + L5 only");
        tvStatus = tv(16, Color.LTGRAY, false);
        tvCoords = tv(34, Color.YELLOW, true);
        tvAcc = tv(20, Color.WHITE, false);
        tvAvg = tv(18, Color.rgb(120, 220, 255), false);
        tvSats = tv(15, Color.LTGRAY, false);
        tvPhone = tv(14, Color.GRAY, false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(btn("SOS SMS", Color.rgb(200, 0, 0), v -> sendSms()), weight());
        row.addView(btn("SHARE", Color.DKGRAY, v -> share()), weight());
        row.addView(btn("COPY", Color.DKGRAY, v -> copy()), weight());

        root.addView(title);
        root.addView(tvStatus);
        root.addView(tvCoords);
        root.addView(tvAcc);
        root.addView(tvAvg);
        root.addView(row);
        root.addView(tvSats);
        root.addView(tvPhone);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(root);
        setContentView(sv);
        tvCoords.setText("No fix yet");
        refresh();
    }

    private TextView tv(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 10, 0, 10);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button btn(String label, int color, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 160, 1f);
        p.setMargins(8, 20, 8, 20);
        return p;
    }

    @Override
    protected void onStart() {
        super.onStart();
        start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stop();
        saveStore();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) start();
        else tvStatus.setText("Precise location permission is required.");
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (running) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper());
            if (Build.VERSION.SDK_INT >= 31) {
                GnssMeasurementRequest req = new GnssMeasurementRequest.Builder().setFullTracking(true).build();
                lm.registerGnssMeasurementsCallback(req, getMainExecutor(), measCb);
            } else {
                lm.registerGnssMeasurementsCallback(measCb, handler);
            }
            lm.registerGnssNavigationMessageCallback(navCb, handler);
            running = true;
        } catch (Exception e) {
            tvStatus.setText("GNSS start failed: " + e.getMessage());
        }
        refresh();
    }

    private void stop() {
        if (!running) return;
        try {
            lm.removeUpdates(locListener);
            lm.unregisterGnssMeasurementsCallback(measCb);
            lm.unregisterGnssNavigationMessageCallback(navCb);
        } catch (Exception ignored) {
        }
        running = false;
    }

    private void saveStore() {
        prefs.edit().putString("store", store.serialize()).apply();
        lastSave = System.currentTimeMillis();
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { phoneLoc = l; refresh(); }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { tvStatus.setText("Location is OFF — turn it on"); }
    };

    private final GnssNavigationMessage.Callback navCb = new GnssNavigationMessage.Callback() {
        @Override
        public void onGnssNavigationMessageReceived(GnssNavigationMessage m) {
            if (m.getType() != GnssNavigationMessage.TYPE_GPS_L1CA) return;
            if (m.getStatus() == GnssNavigationMessage.STATUS_UNKNOWN || rxWeek < 0) return;
            Ephemeris e = decoder.onMessage(m.getSvid(), m.getData(), rxWeek);
            if (e != null && store.add(e) && System.currentTimeMillis() - lastSave > 10000) saveStore();
        }

        @Override
        public void onStatusChanged(int s) {
            navStatus = s == GnssNavigationMessage.Callback.STATUS_READY ? "ok"
                    : s == GnssNavigationMessage.Callback.STATUS_NOT_SUPPORTED ? "NOT SUPPORTED by phone" : "location off";
            handler.post(MainActivity.this::refresh);
        }
    };

    private final GnssMeasurementsEvent.Callback measCb = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent ev) {
            process(ev);
            handler.post(MainActivity.this::refresh);
        }

        @Override
        public void onStatusChanged(int s) {
            measStatus = s == GnssMeasurementsEvent.Callback.STATUS_READY ? "ok"
                    : s == GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED ? "NOT SUPPORTED by phone" : "location off";
            handler.post(MainActivity.this::refresh);
        }
    };

    private static final class Raw {
        final double pr, tTx, sigma;
        Raw(double pr, double tTx, double sigma) { this.pr = pr; this.tTx = tTx; this.sigma = sigma; }
    }

    private void process(GnssMeasurementsEvent ev) {
        GnssClock clk = ev.getClock();
        if (!clk.hasFullBiasNanos()) return;
        long fullBias = clk.getFullBiasNanos();
        double bias = clk.hasBiasNanos() ? clk.getBiasNanos() : 0;
        long week = (-fullBias) / WEEK_NS;
        double tRxBase = (double) (clk.getTimeNanos() - fullBias - week * WEEK_NS) - bias;
        rxWeek = (int) week;
        rxTow = tRxBase * 1e-9;

        Map<Integer, Raw> l1 = new HashMap<>(), l5 = new HashMap<>();
        for (GnssMeasurement m : ev.getMeasurements()) {
            if (m.getConstellationType() != GnssStatus.CONSTELLATION_GPS) continue;
            double f = m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : Solver.F_L1;
            boolean isL1 = Math.abs(f - Solver.F_L1) < 2e6, isL5 = Math.abs(f - Solver.F_L5) < 2e6;
            if (!isL1 && !isL5) continue; // rejects L2 and everything else
            if (m.hasCodeType()) {
                String c = m.getCodeType();
                if (isL1 && !"C".equals(c)) continue; // L1 C/A only (no L1C)
                if (isL5 && !("I".equals(c) || "Q".equals(c) || "X".equals(c))) continue;
            }
            int st = m.getState();
            if ((st & (GnssMeasurement.STATE_TOW_DECODED | GnssMeasurement.STATE_TOW_KNOWN)) == 0) continue;
            if (m.getReceivedSvTimeUncertaintyNanos() > 500) continue;
            double tRx = tRxBase + m.getTimeOffsetNanos();
            double prNs = tRx - m.getReceivedSvTimeNanos();
            if (prNs < -WEEK_NS / 2.0) prNs += WEEK_NS;
            double pr = prNs * 1e-9 * Solver.C;
            if (pr < 1.5e7 || pr > 3.5e7) continue;
            double sigma = Math.max(m.getReceivedSvTimeUncertaintyNanos() * 1e-9 * Solver.C, 1.0);
            Raw r = new Raw(pr, m.getReceivedSvTimeNanos() * 1e-9, sigma);
            if (isL1) l1.put(m.getSvid(), r); else l5.put(m.getSvid(), r);
        }
        nL1 = l1.size();
        nL5 = l5.size();
        nEph = store.usableCount(rxWeek, rxTow);

        List<Solver.Obs> dual = new ArrayList<>(), single = new ArrayList<>();
        for (Map.Entry<Integer, Raw> en : l1.entrySet()) {
            Ephemeris e = store.best(en.getKey(), rxWeek, rxTow);
            if (e == null || e.health != 0) continue;
            Raw a = en.getValue();
            single.add(new Solver.Obs(e, a.pr, a.tTx, a.sigma, true));
            Raw b = l5.get(en.getKey());
            if (b != null) {
                double prIf = Solver.IF_A * a.pr - Solver.IF_B * b.pr;
                double sIf = Math.hypot(Solver.IF_A * a.sigma, Solver.IF_B * b.sigma);
                dual.add(new Solver.Obs(e, prIf, a.tTx, sIf, false));
            }
        }
        List<Solver.Obs> use;
        String mode;
        if (dual.size() >= 5) { use = dual; mode = "L1+L5 iono-free"; }
        else { use = single; mode = "L1 C/A only"; }
        Solver.Solution s = Solver.solve(use, seed);
        if (s == null && seed != null) s = Solver.solve(use, null);
        if (s == null || s.acc > 500 || Double.isNaN(s.acc)) return;
        seed = s.x;
        fix = s;
        fixMode = mode;
        fixTime = System.currentTimeMillis();
        if (!avg.isEmpty()) {
            double[] m = mean();
            if (distM(m[0], m[1], s.lat, s.lon) > 100) avg.clear();
        }
        avg.addLast(new double[]{s.lat, s.lon});
        while (avg.size() > 60) avg.removeFirst();
    }

    private double[] mean() {
        double la = 0, lo = 0;
        for (double[] p : avg) { la += p[0]; lo += p[1]; }
        return new double[]{la / avg.size(), lo / avg.size()};
    }

    private static double distM(double la1, double lo1, double la2, double lo2) {
        double k = 111320;
        return Math.hypot((la1 - la2) * k, (lo1 - lo2) * k * Math.cos(Math.toRadians(la1)));
    }

    private void refresh() {
        boolean fresh = fix != null && System.currentTimeMillis() - fixTime < 5000;
        String ephNote = navStatus.startsWith("NOT")
                ? "Phone can't give nav data: ephemeris only from earlier saves."
                : "Ephemeris decodes from sky (~30 s per satellite, needs clear view).";
        tvStatus.setText(String.format(Locale.US,
                "Raw meas: %s · Nav data: %s\nGPS seen: %d L1 C/A, %d L5 · ephemeris: %d sats\n%s",
                measStatus, navStatus, nL1, nL5, nEph, ephNote));
        if (fix != null) {
            tvCoords.setText(String.format(Locale.US, "%.6f\n%.6f", fix.lat, fix.lon));
            tvCoords.setTextColor(fresh ? Color.YELLOW : Color.GRAY);
            tvAcc.setText(String.format(Locale.US, "±%.0f m  ·  alt %.0f m  ·  %d sats  ·  HDOP %.1f\n%s%s",
                    fix.acc, fix.alt, fix.used, fix.hdop, fixMode, fresh ? "" : "  ·  (OLD FIX)"));
            double[] m = mean();
            tvAvg.setText(String.format(Locale.US, "Average of %d fixes (stay still): %.6f, %.6f",
                    avg.size(), m[0], m[1]));
        }
        StringBuilder sb = new StringBuilder();
        if (fix == null && nEph < 4)
            sb.append("Need ephemeris for 4+ satellites. Go outside with open sky and wait 1-2 min.\n");
        tvSats.setText(sb.toString());
        if (phoneLoc != null)
            tvPhone.setText(String.format(Locale.US,
                    "Phone's own fix (all signals, for comparison): %.6f, %.6f ±%.0f m",
                    phoneLoc.getLatitude(), phoneLoc.getLongitude(), phoneLoc.getAccuracy()));
    }

    private String message() {
        double la, lo, acc;
        String src;
        if (fix != null) { la = fix.lat; lo = fix.lon; acc = fix.acc; src = "GPS " + fixMode; }
        else if (phoneLoc != null) { la = phoneLoc.getLatitude(); lo = phoneLoc.getLongitude(); acc = phoneLoc.getAccuracy(); src = "phone GPS"; }
        else return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return String.format(Locale.US, "SOS! I need help. My location: %.6f, %.6f (±%.0f m, %s, %s) https://maps.google.com/?q=%.6f,%.6f",
                la, lo, acc, src, f.format(new Date()), la, lo);
    }

    private void sendSms() {
        String msg = message();
        if (msg == null) { toast("No position yet"); return; }
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"));
        i.putExtra("sms_body", msg);
        try { startActivity(i); } catch (Exception e) { toast("No SMS app"); }
    }

    private void share() {
        String msg = message();
        if (msg == null) { toast("No position yet"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(i, "Send location"));
    }

    private void copy() {
        String msg = message();
        if (msg == null) { toast("No position yet"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("location", msg));
        toast("Copied");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
