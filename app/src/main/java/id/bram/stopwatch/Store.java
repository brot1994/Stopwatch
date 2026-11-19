package id.bram.stopwatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Semua data aplikasi: stopwatch, timer, riwayat, pengaturan. */
public class Store {

    public interface Listener { void onStoreChanged(); }
    public static Listener listener;

    private static Store inst;
    public static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    public static int bootCount = -1;

    final Context ctx;
    final SharedPreferences sp;

    public final ArrayList<Sw> sws = new ArrayList<>();
    public int active = 0;
    public final ArrayList<Hist> history = new ArrayList<>();
    public final Tm timer = new Tm();

    // Pengaturan
    public int theme;        // 0 ikut sistem, 1 terang, 2 gelap, 3 hitam (AMOLED)
    public int volMode;      // 0 Up=Lap/Down=Start-Jeda, 1 ditukar, 2 mati
    public boolean keepOn;   // layar tetap menyala saat berjalan
    public boolean haptic;   // getar kecil saat tombol ditekan
    public boolean autoHistory; // simpan ke riwayat saat Reset
    public int voiceLap;     // 0 mati, 1 waktu lap, 2 total, 3 keduanya
    public boolean voicePause; // sebut total saat Jeda
    public int voiceLang;    // 0 Indonesia, 1 Inggris
    public int voiceRate;    // 0 pelan, 1 normal, 2 cepat, 3 sangat cepat
    public String jamendoKey = "";
    public int soundMode;    // 0 suara (sebut waktu), 1 bip, 2 senyap
    public int beepType;     // 0 bip, 1 klik, 2 ding, 3 bip ganda
    public int ovPos;        // posisi angka di video: 0 kiri bawah, 1 kanan bawah, 2 kiri atas, 3 kanan atas
    public int ovSize;       // 0 kecil, 1 sedang, 2 besar
    public boolean recLandscape; // rekam mendatar

    private Store(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("data", Context.MODE_PRIVATE);
        try { bootCount = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (Exception e) { bootCount = -1; }
        load();
    }

    // ---------- waktu ----------
    /** Lama waktu sejak titik (rt, wall) dicatat pada boot ke-'boot'. */
    public static long since(long rt, int boot, long wall) {
        long now = SystemClock.elapsedRealtime();
        if (boot == bootCount && boot != -1 && now >= rt) return now - rt;
        if (boot == -1 && bootCount == -1 && now >= rt) return now - rt;
        return Math.max(0, System.currentTimeMillis() - wall);
    }

    // ---------- muat / simpan ----------
    private void load() {
        theme = sp.getInt("theme", 0);
        volMode = sp.getInt("volMode", 0);
        keepOn = sp.getBoolean("keepOn", false);
        haptic = sp.getBoolean("haptic", true);
        autoHistory = sp.getBoolean("autoHistory", true);
        active = sp.getInt("active", 0);
        voiceLap = sp.getInt("voiceLap", 1);
        voicePause = sp.getBoolean("voicePause", false);
        voiceLang = sp.getInt("voiceLang", 0);
        voiceRate = sp.getInt("voiceRate", 1);
        ovPos = sp.getInt("ovPos", 0);
        soundMode = sp.getInt("soundMode", 0);
        jamendoKey = sp.getString("jamendoKey", "");
        beepType = sp.getInt("beepType", 0);
        ovSize = sp.getInt("ovSize", 1);
        recLandscape = sp.getBoolean("recLandscape", false);
        try {
            JSONArray a = new JSONArray(sp.getString("sws", "[]"));
            for (int i = 0; i < a.length(); i++) sws.add(Sw.from(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        try {
            JSONArray a = new JSONArray(sp.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) history.add(Hist.from(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        try {
            String t = sp.getString("timer", null);
            if (t != null) timer.read(new JSONObject(t));
        } catch (Exception ignored) {}
        if (sws.isEmpty()) sws.add(newSw());
        if (active < 0 || active >= sws.size()) active = 0;
    }

    public void save() {
        try {
            JSONArray a = new JSONArray();
            for (Sw s : sws) a.put(s.json());
            JSONArray h = new JSONArray();
            for (Hist x : history) h.put(x.json());
            sp.edit()
                    .putString("sws", a.toString())
                    .putString("history", h.toString())
                    .putString("timer", timer.json().toString())
                    .putInt("active", active)
                    .putInt("theme", theme)
                    .putInt("volMode", volMode)
                    .putBoolean("keepOn", keepOn)
                    .putBoolean("haptic", haptic)
                    .putBoolean("autoHistory", autoHistory)
                    .putInt("voiceLap", voiceLap)
                    .putBoolean("voicePause", voicePause)
                    .putInt("voiceLang", voiceLang)
                    .putInt("voiceRate", voiceRate)
                    .putInt("ovPos", ovPos)
                    .putInt("soundMode", soundMode)
                    .putString("jamendoKey", jamendoKey)
                    .putInt("beepType", beepType)
                    .putInt("ovSize", ovSize)
                    .putBoolean("recLandscape", recLandscape)
                    .apply();
        } catch (Exception ignored) {}
    }

    /** Simpan, perbarui notifikasi, dan beri tahu layar. */
    public void changed() {
        save();
        Notif.refresh(ctx);
        if (listener != null) listener.onStoreChanged();
    }

    // ---------- stopwatch ----------
    public Sw newSw() {
        Sw s = new Sw();
        s.id = System.currentTimeMillis() + (long) (Math.random() * 1000);
        int n = 1;
        boolean taken;
        do {
            taken = false;
            for (Sw o : sws) if (o.name.equals("Stopwatch " + n)) { taken = true; n++; break; }
        } while (taken);
        s.name = "Stopwatch " + n;
        return s;
    }

    public Sw activeSw() { return sws.get(Math.min(active, sws.size() - 1)); }

    public Sw byId(long id) {
        for (Sw s : sws) if (s.id == id) return s;
        return null;
    }

    public void start(Sw s) {
        if (s.running) return;
        s.startRt = SystemClock.elapsedRealtime();
        s.startWall = System.currentTimeMillis();
        s.boot = bootCount;
        if (s.acc == 0 && s.laps.isEmpty()) s.sessionWall = s.startWall;
        s.running = true;
    }

    public void pause(Sw s) {
        if (!s.running) return;
        s.acc = s.elapsed();
        s.running = false;
    }

    public void toggle(Sw s) {
        if (s.running) { pause(s); Speaker.onPause(ctx, s); }
        else { start(s); Speaker.onStart(ctx, s); }
    }

    public static final String[] SOUND_NAMES = {"Suara", "Bip", "Senyap"};

    public void cycleSound() { soundMode = (soundMode + 1) % 3; save(); }

    public static int soundIcon(int mode) {
        return mode == 0 ? R.drawable.ic_vol_up : mode == 1 ? R.drawable.ic_bell : R.drawable.ic_vol_off;
    }

    public void lap(Sw s) {
        if (!s.running) return;
        s.laps.add(s.elapsed());
        Speaker.onLap(ctx, s);
    }

    /** Reset; kalau ada waktu tercatat dan autoHistory aktif, masuk riwayat. */
    public void reset(Sw s) { reset(s, null, false); }

    /** Reset; videoUri != null berarti sesi ini punya video. force = selalu simpan ke riwayat. */
    public Hist reset(Sw s, String videoUri, boolean force) {
        Hist out = null;
        if (s.running) pause(s);
        if (s.acc > 0 && (autoHistory || force)) {
            Hist h = new Hist();
            h.video = videoUri == null ? "" : videoUri;
            out = h;
            h.id = System.currentTimeMillis();
            h.name = s.name;
            h.note = "";
            h.startWall = s.sessionWall > 0 ? s.sessionWall : System.currentTimeMillis() - s.acc;
            h.endWall = System.currentTimeMillis();
            h.total = s.acc;
            h.laps.addAll(s.laps);
            history.add(0, h);
        }
        s.acc = 0;
        s.laps.clear();
        s.sessionWall = 0;
        return out;
    }

    public boolean anyRunning() {
        for (Sw s : sws) if (s.running) return true;
        return timer.state == Tm.RUNNING;
    }

    // ---------- timer ----------
    public void timerStart() {
        Tm t = timer;
        if (t.state == Tm.IDLE || t.state == Tm.DONE) t.remain = t.duration;
        if (t.remain <= 0) return;
        t.endRt = SystemClock.elapsedRealtime() + t.remain;
        t.endWall = System.currentTimeMillis() + t.remain;
        t.boot = bootCount;
        t.state = Tm.RUNNING;
        TimerReceiver.schedule(ctx, t.remain);
    }

    public void timerPause() {
        Tm t = timer;
        if (t.state != Tm.RUNNING) return;
        t.remain = t.remaining();
        t.state = Tm.PAUSED;
        TimerReceiver.cancel(ctx);
    }

    public void timerCancel() {
        timer.state = Tm.IDLE;
        timer.remain = timer.duration;
        TimerReceiver.cancel(ctx);
        Notif.stopAlarm(ctx);
    }

    public void timerAdd(long ms) {
        Tm t = timer;
        if (t.state == Tm.RUNNING) {
            t.endRt += ms;
            t.endWall += ms;
            TimerReceiver.schedule(ctx, t.remaining());
        } else if (t.state == Tm.PAUSED) {
            t.remain += ms;
        }
    }

    // ---------- format ----------
    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long cs = (ms / 10) % 100;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000) % 60;
        long h = ms / 3600000;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
        return String.format(java.util.Locale.US, "%02d:%02d.%02d", m, s, cs);
    }

    public static String fmtShort(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000) % 60;
        long h = ms / 3600000;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    /** Untuk timer: dibulatkan ke atas per detik. */
    public static String fmtDown(long ms) {
        if (ms < 0) ms = 0;
        return fmtShort(((ms + 999) / 1000) * 1000);
    }

    // ================= model =================
    public static class Sw {
        public long id;
        public String name = "";
        public boolean running;
        public long acc, startRt, startWall, sessionWall;
        public int boot = -1;
        public final ArrayList<Long> laps = new ArrayList<>();

        public long elapsed() { return running ? acc + since(startRt, boot, startWall) : acc; }

        public Sw copy() {
            Sw c = new Sw();
            c.id = id; c.name = name; c.running = running; c.acc = acc; c.startRt = startRt;
            c.startWall = startWall; c.sessionWall = sessionWall; c.boot = boot;
            c.laps.addAll(laps);
            return c;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("running", running);
            o.put("acc", acc); o.put("startRt", startRt); o.put("startWall", startWall);
            o.put("sessionWall", sessionWall); o.put("boot", boot);
            JSONArray l = new JSONArray();
            for (Long x : laps) l.put(x);
            o.put("laps", l);
            return o;
        }

        static Sw from(JSONObject o) {
            Sw s = new Sw();
            s.id = o.optLong("id"); s.name = o.optString("name", "Stopwatch");
            s.running = o.optBoolean("running"); s.acc = o.optLong("acc");
            s.startRt = o.optLong("startRt"); s.startWall = o.optLong("startWall");
            s.sessionWall = o.optLong("sessionWall"); s.boot = o.optInt("boot", -1);
            JSONArray l = o.optJSONArray("laps");
            if (l != null) for (int i = 0; i < l.length(); i++) s.laps.add(l.optLong(i));
            return s;
        }
    }

    public static class Hist {
        public long id, startWall, endWall, total;
        public String name = "", note = "", video = "", videoMusic = "";
        public final ArrayList<Long> laps = new ArrayList<>();

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("note", note); o.put("video", video); o.put("videoMusic", videoMusic);
            o.put("startWall", startWall); o.put("endWall", endWall); o.put("total", total);
            JSONArray l = new JSONArray();
            for (Long x : laps) l.put(x);
            o.put("laps", l);
            return o;
        }

        static Hist from(JSONObject o) {
            Hist h = new Hist();
            h.id = o.optLong("id"); h.name = o.optString("name"); h.note = o.optString("note"); h.video = o.optString("video", ""); h.videoMusic = o.optString("videoMusic", "");
            h.startWall = o.optLong("startWall"); h.endWall = o.optLong("endWall"); h.total = o.optLong("total");
            JSONArray l = o.optJSONArray("laps");
            if (l != null) for (int i = 0; i < l.length(); i++) h.laps.add(l.optLong(i));
            return h;
        }
    }

    public static class Tm {
        public static final int IDLE = 0, RUNNING = 1, PAUSED = 2, DONE = 3;
        public int state = IDLE;
        public long duration = 5 * 60000L;
        public long remain = 5 * 60000L;
        public long endRt, endWall;
        public int boot = -1;

        public long remaining() {
            if (state == RUNNING) {
                long now = SystemClock.elapsedRealtime();
                long r;
                if (boot == bootCount && now <= endRt + 86400000L) r = endRt - now;
                else r = endWall - System.currentTimeMillis();
                return Math.max(0, r);
            }
            if (state == DONE) return 0;
            if (state == IDLE) return duration;
            return remain;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("state", state); o.put("duration", duration); o.put("remain", remain);
            o.put("endRt", endRt); o.put("endWall", endWall); o.put("boot", boot);
            return o;
        }

        void read(JSONObject o) {
            state = o.optInt("state"); duration = o.optLong("duration", 300000);
            remain = o.optLong("remain", duration); endRt = o.optLong("endRt");
            endWall = o.optLong("endWall"); boot = o.optInt("boot", -1);
        }
    }
}
