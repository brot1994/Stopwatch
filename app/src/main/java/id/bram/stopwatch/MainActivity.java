package id.bram.stopwatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements Store.Listener {

    static final int REQ_EXPORT = 11;

    Store st;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean visible;
    int page = 0;

    // Warna
    int cBg, cSurf, cSurf2, cText, cSub, cAcc, cOnAcc, cGreen, cRed, cOrange;
    boolean dark;

    FrameLayout content;
    View pgSw, pgTm, pgHist;
    TextView[] tabs = new TextView[3];
    ImageView camBtn;

    // Stopwatch
    LinearLayout chipRow;
    final ArrayList<TextView> chips = new ArrayList<>();
    TextView swName, bigTime, lapNow;
    LinearLayout lapList;
    TextView bReset, bLap, bStart;
    int shownLaps = -1;
    long shownSw = -1;
    String chipSig = "";
    String btnSig = "";

    // Timer
    View tmSetup, tmRun;
    NumberPicker npH, npM, npS;
    TextView tmBig, tmStatus;
    ProgressBar tmProg;
    TextView tLeft, tMid, tRight;
    int shownTmState = -1;

    // Riwayat
    TextView histTitle, histEmpty;
    ListView histList;
    HistAdapter histAdapter;

    boolean keepOnApplied = false;

    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        st = Store.get(this);
        computeColors();
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cSurf);
        if (!dark) {
            int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
        buildUi();
        if (st.voiceLap != 0 || st.voicePause) Speaker.init(this);
        if (b != null) page = b.getInt("page", 0);
        handleIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    void handleIntent(Intent i) {
        if (i == null) { showPage(page); return; }
        int p = i.getIntExtra("page", -1);
        long sw = i.getLongExtra("sw", 0);
        if (sw != 0) {
            for (int k = 0; k < st.sws.size(); k++) if (st.sws.get(k).id == sw) st.active = k;
        }
        if (p == 1 && st.timer.state == Store.Tm.DONE) {
            // dibuka dari alarm: hentikan bunyi
            Notif.stopAlarm(this);
        }
        showPage(p >= 0 ? p : page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        Store.listener = this;
        onStoreChanged();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        Store.listener = null;
        h.removeCallbacks(tick);
        st.save();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            updateTimes();
            h.postDelayed(this, 33);
        }
    };

    @Override
    public void onStoreChanged() {
        if (st.active >= st.sws.size()) st.active = st.sws.size() - 1;
        chipSig = "";
        btnSig = "";
        shownLaps = -1;
        shownTmState = -1;
        updateTimes();
        if (histAdapter != null) refreshHistory();
    }

    // =====================================================================
    //  Warna & helper
    // =====================================================================
    void computeColors() {
        int t = st.theme;
        if (t == 0) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            t = night == Configuration.UI_MODE_NIGHT_YES ? 2 : 1;
        }
        dark = t != 1;
        if (t == 1) {
            cBg = 0xFFF3F5F8; cSurf = 0xFFFFFFFF; cSurf2 = 0xFFE4E8EE; cText = 0xFF14171C; cSub = 0xFF6B7280;
            cAcc = 0xFF1565C0; cOnAcc = 0xFFFFFFFF; cGreen = 0xFF1B8A3A; cRed = 0xFFD32F2F; cOrange = 0xFFE65100;
        } else if (t == 2) {
            cBg = 0xFF121418; cSurf = 0xFF1D2026; cSurf2 = 0xFF2A2E36; cText = 0xFFF1F3F5; cSub = 0xFF9AA1AC;
            cAcc = 0xFF4FA3FF; cOnAcc = 0xFF0B1220; cGreen = 0xFF4CD07D; cRed = 0xFFFF6B6B; cOrange = 0xFFFFB74D;
        } else {
            cBg = 0xFF000000; cSurf = 0xFF0E0E10; cSurf2 = 0xFF1C1C20; cText = 0xFFFFFFFF; cSub = 0xFF8E8E95;
            cAcc = 0xFF4FA3FF; cOnAcc = 0xFF000000; cGreen = 0xFF4CD07D; cRed = 0xFFFF6B6B; cOrange = 0xFFFFB74D;
        }
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    Drawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    Drawable ripple(int color, float radiusDp) {
        int rc = dark ? 0x33FFFFFF : 0x22000000;
        return new RippleDrawable(ColorStateList.valueOf(rc), round(color, radiusDp), round(0xFFFFFFFF, radiusDp));
    }

    TextView tv(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    TextView btn(String s, int bg, int fg) {
        TextView t = tv(s, 18, fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(ripple(bg, 32));
        t.setClickable(true);
        t.setFocusable(true);
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    void styleBtn(TextView b, String txt, int bg, int fg, boolean enabled) {
        b.setText(txt);
        b.setBackground(ripple(bg, 32));
        b.setTextColor(fg);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.35f);
    }

    void buzz(View v) {
        if (st.haptic && v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    LinearLayout.LayoutParams lp(int w, int hgt, float weight) {
        return new LinearLayout.LayoutParams(w, hgt, weight);
    }

    static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    Typeface digits() { return Typeface.create("sans-serif-light", Typeface.NORMAL); }

    // =====================================================================
    //  Bangun UI
    // =====================================================================
    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        // Bilah atas
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(10), dp(8), dp(4));
        TextView title = tv("Stopwatch", 22, cText);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, lp(0, WC, 1));
        camBtn = new ImageView(this);
        camBtn.setImageResource(R.drawable.ic_videocam);
        camBtn.setColorFilter(cAcc, PorterDuff.Mode.SRC_IN);
        camBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        camBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), null, null));
        camBtn.setOnClickListener(v -> startActivity(new Intent(this, RecordActivity.class)));
        top.addView(camBtn, lp(dp(48), dp(48), 0));
        ImageView set = new ImageView(this);
        set.setImageResource(R.drawable.ic_settings);
        set.setColorFilter(cSub, PorterDuff.Mode.SRC_IN);
        set.setPadding(dp(12), dp(12), dp(12), dp(12));
        set.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), null, null));
        set.setOnClickListener(v -> showSettings());
        top.addView(set, lp(dp(48), dp(48), 0));
        root.addView(top, lp(MP, WC, 0));

        content = new FrameLayout(this);
        root.addView(content, lp(MP, 0, 1));

        pgSw = buildStopwatchPage();
        pgTm = buildTimerPage();
        pgHist = buildHistoryPage();
        content.addView(pgSw, new FrameLayout.LayoutParams(MP, MP));
        content.addView(pgTm, new FrameLayout.LayoutParams(MP, MP));
        content.addView(pgHist, new FrameLayout.LayoutParams(MP, MP));

        // Tab bawah
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(cSurf);
        nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] names = {"Stopwatch", "Timer", "Riwayat"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = tv(names[i], 15, cSub);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> showPage(idx));
            tabs[i] = t;
            LinearLayout.LayoutParams l = lp(0, WC, 1);
            l.setMargins(dp(4), 0, dp(4), 0);
            nav.addView(t, l);
        }
        root.addView(nav, lp(MP, WC, 0));
        setContentView(root);
    }

    void showPage(int p) {
        page = p;
        pgSw.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pgTm.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pgHist.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        camBtn.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) {
            boolean on = i == p;
            tabs[i].setTextColor(on ? cOnAcc : cSub);
            tabs[i].setBackground(on ? round(cAcc, 22) : null);
            tabs[i].setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
        if (p == 2) refreshHistory();
        onStoreChanged();
    }

    // ---------------------------------------------------------------------
    View buildStopwatchPage() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), 0, dp(16), dp(12));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setPadding(0, dp(4), 0, dp(4));
        hs.addView(chipRow);
        v.addView(hs, lp(MP, WC, 0));

        swName = tv("", 15, cSub);
        swName.setGravity(Gravity.CENTER);
        swName.setPadding(0, dp(14), 0, 0);
        swName.setOnClickListener(x -> renameSw(st.activeSw()));
        v.addView(swName, lp(MP, WC, 0));

        bigTime = new TextView(this);
        bigTime.setTextColor(cText);
        bigTime.setGravity(Gravity.CENTER);
        bigTime.setTypeface(digits());
        bigTime.setFontFeatureSettings("tnum");
        bigTime.setMaxLines(1);
        bigTime.setAutoSizeTextTypeUniformWithConfiguration(28, 150, 2, TypedValue.COMPLEX_UNIT_SP);
        bigTime.setOnLongClickListener(x -> { renameSw(st.activeSw()); return true; });
        v.addView(bigTime, lp(MP, dp(130), 0));

        lapNow = tv("", 18, cSub);
        lapNow.setGravity(Gravity.CENTER);
        lapNow.setTypeface(digits());
        lapNow.setFontFeatureSettings("tnum");
        v.addView(lapNow, lp(MP, WC, 0));

        // kepala tabel lap
        LinearLayout hd = lapRow("Lap", "Waktu lap", "Total", cSub, cSub, 13);
        hd.setPadding(dp(12), dp(14), dp(12), dp(6));
        v.addView(hd, lp(MP, WC, 0));

        ScrollView sc = new ScrollView(this);
        sc.setBackground(round(cSurf, 16));
        lapList = new LinearLayout(this);
        lapList.setOrientation(LinearLayout.VERTICAL);
        lapList.setPadding(dp(12), dp(6), dp(12), dp(6));
        sc.addView(lapList);
        v.addView(sc, lp(MP, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(0, dp(12), 0, 0);
        bReset = btn("Reset", cSurf2, cText);
        bLap = btn("Lap", cSurf2, cText);
        bStart = btn("Mulai", cAcc, cOnAcc);
        LinearLayout.LayoutParams l1 = lp(0, dp(64), 1); l1.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams l2 = lp(0, dp(64), 1); l2.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams l3 = lp(0, dp(64), 1.4f);
        bar.addView(bReset, l1);
        bar.addView(bLap, l2);
        bar.addView(bStart, l3);
        v.addView(bar, lp(MP, WC, 0));

        bStart.setOnClickListener(x -> { buzz(x); swStartPause(); });
        bLap.setOnClickListener(x -> { buzz(x); swLap(); });
        bReset.setOnClickListener(x -> { buzz(x); swReset(); });
        return v;
    }

    LinearLayout lapRow(String a, String b, String c, int colA, int colB, float sp) {
        LinearLayout r = new LinearLayout(this);
        TextView t1 = tv(a, sp, colA);
        TextView t2 = tv(b, sp, colB);
        TextView t3 = tv(c, sp, colA);
        t2.setGravity(Gravity.END);
        t3.setGravity(Gravity.END);
        Typeface d = Typeface.create("sans-serif", Typeface.NORMAL);
        for (TextView t : new TextView[]{t1, t2, t3}) { t.setTypeface(d); t.setFontFeatureSettings("tnum"); }
        r.addView(t1, lp(0, WC, 0.7f));
        r.addView(t2, lp(0, WC, 1.3f));
        r.addView(t3, lp(0, WC, 1.3f));
        return r;
    }

    void swStartPause() { st.toggle(st.activeSw()); st.changed(); }

    void swLap() {
        Store.Sw s = st.activeSw();
        if (!s.running) return;
        st.lap(s);
        st.changed();
    }

    void swReset() {
        Store.Sw s = st.activeSw();
        if (s.running || s.acc == 0) return;
        st.reset(s);
        st.changed();
        if (st.autoHistory) Toast.makeText(this, "Tersimpan di Riwayat", Toast.LENGTH_SHORT).show();
    }

    void rebuildChips() {
        chipRow.removeAllViews();
        chips.clear();
        for (int i = 0; i < st.sws.size(); i++) {
            final int idx = i;
            Store.Sw s = st.sws.get(i);
            TextView c = tv("", 13, cText);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(14), dp(7), dp(14), dp(7));
            c.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            c.setFontFeatureSettings("tnum");
            boolean sel = i == st.active;
            c.setBackground(ripple(sel ? cAcc : cSurf2, 18));
            c.setTextColor(sel ? cOnAcc : cText);
            c.setOnClickListener(x -> { st.active = idx; st.save(); onStoreChanged(); });
            c.setOnLongClickListener(x -> { swMenu(st.sws.get(idx)); return true; });
            LinearLayout.LayoutParams l = lp(WC, WC, 0);
            l.setMargins(0, 0, dp(8), 0);
            chipRow.addView(c, l);
            chips.add(c);
        }
        TextView add = tv("+  Tambah", 13, cAcc);
        add.setPadding(dp(14), dp(7), dp(14), dp(7));
        add.setBackground(ripple(cSurf2, 18));
        add.setOnClickListener(x -> {
            st.sws.add(st.newSw());
            st.active = st.sws.size() - 1;
            st.changed();
        });
        chipRow.addView(add, lp(WC, WC, 0));
    }

    void swMenu(Store.Sw s) {
        String[] items = st.sws.size() > 1 ? new String[]{"Ganti nama", "Hapus stopwatch ini"} : new String[]{"Ganti nama"};
        new AlertDialog.Builder(this).setTitle(s.name).setItems(items, (d, w) -> {
            if (w == 0) renameSw(s);
            else {
                new AlertDialog.Builder(this).setTitle("Hapus " + s.name + "?")
                        .setMessage(s.acc > 0 || s.running ? "Waktu yang sedang tercatat akan disimpan ke Riwayat dulu (jika diaktifkan)." : null)
                        .setPositiveButton("Hapus", (d2, w2) -> {
                            if (s.running) st.pause(s);
                            if (s.acc > 0) st.reset(s);
                            st.sws.remove(s);
                            if (st.active >= st.sws.size()) st.active = st.sws.size() - 1;
                            st.changed();
                        }).setNegativeButton("Batal", null).show();
            }
        }).show();
    }

    void renameSw(Store.Sw s) {
        EditText e = new EditText(this);
        e.setText(s.name);
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(e);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Nama stopwatch").setView(f)
                .setPositiveButton("Simpan", (x, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) { s.name = n; st.changed(); }
                }).setNegativeButton("Batal", null).create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
    }

    CharSequence bigSpan(long ms) {
        String s = Store.fmt(ms);
        SpannableString sp = new SpannableString(s);
        int dot = s.lastIndexOf('.');
        if (dot > 0) sp.setSpan(new RelativeSizeSpan(0.55f), dot, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    void updateStopwatch() {
        // chip
        StringBuilder sig = new StringBuilder();
        sig.append(st.active).append('|');
        for (Store.Sw s : st.sws) sig.append(s.id).append(s.name).append(',');
        if (!sig.toString().equals(chipSig)) { chipSig = sig.toString(); rebuildChips(); }
        for (int i = 0; i < chips.size() && i < st.sws.size(); i++) {
            Store.Sw s = st.sws.get(i);
            String dot = s.running ? "● " : "";
            chips.get(i).setText(dot + s.name + "  " + Store.fmtShort(s.elapsed()));
        }

        Store.Sw s = st.activeSw();
        long el = s.elapsed();
        swName.setText(s.name + "  ✎");
        bigTime.setText(bigSpan(el));
        bigTime.setTextColor(s.running ? cText : (el > 0 ? cOrange : cText));

        long last = s.laps.isEmpty() ? 0 : s.laps.get(s.laps.size() - 1);
        if (el > 0) lapNow.setText("Lap " + (s.laps.size() + 1) + "   " + Store.fmt(el - last));
        else lapNow.setText(" ");

        if (shownLaps != s.laps.size() || shownSw != s.id) {
            shownLaps = s.laps.size();
            shownSw = s.id;
            fillLaps(lapList, s.laps, -1);
        }

        boolean hasTime = el > 0;
        String sig2 = s.running + "/" + hasTime;
        if (sig2.equals(btnSig)) return;
        btnSig = sig2;
        styleBtn(bStart, s.running ? "Jeda" : (hasTime ? "Lanjut" : "Mulai"),
                s.running ? cOrange : cAcc, s.running ? 0xFF000000 : cOnAcc, true);
        styleBtn(bLap, "Lap", cSurf2, cText, s.running);
        styleBtn(bReset, "Reset", cSurf2, cRed, !s.running && hasTime);
    }

    /** Isi tabel lap (terbaru di atas). total>=0 berarti tambahkan baris "akhir". */
    void fillLaps(LinearLayout list, ArrayList<Long> laps, long total) {
        list.removeAllViews();
        int n = laps.size();
        long fastest = Long.MAX_VALUE, slowest = -1;
        if (n >= 2) {
            for (int i = 0; i < n; i++) {
                long sp = laps.get(i) - (i == 0 ? 0 : laps.get(i - 1));
                fastest = Math.min(fastest, sp);
                slowest = Math.max(slowest, sp);
            }
        }
        if (total >= 0 && n > 0) {
            long lastL = laps.get(n - 1);
            if (total > lastL) {
                LinearLayout r = lapRow("Akhir", "+" + Store.fmt(total - lastL), Store.fmt(total), cSub, cSub, 16);
                r.setPadding(0, dp(9), 0, dp(9));
                list.addView(r);
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            long sp = laps.get(i) - (i == 0 ? 0 : laps.get(i - 1));
            int col = cText;
            if (n >= 2 && sp == fastest) col = cGreen;
            else if (n >= 2 && sp == slowest) col = cRed;
            LinearLayout r = lapRow(String.valueOf(i + 1), Store.fmt(sp), Store.fmt(laps.get(i)), cSub, col, 16);
            r.setPadding(0, dp(9), 0, dp(9));
            list.addView(r);
        }
        if (n == 0 && total < 0) {
            TextView e = tv("Belum ada lap.\nTekan Lap (atau tombol volume) saat stopwatch berjalan.", 14, cSub);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(24), 0, dp(24));
            list.addView(e);
        }
    }

    // ---------------------------------------------------------------------
    View buildTimerPage() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), 0, dp(16), dp(12));

        FrameLayout mid = new FrameLayout(this);
        v.addView(mid, lp(MP, 0, 1));

        // --- tampilan atur waktu
        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setGravity(Gravity.CENTER);
        LinearLayout pick = new LinearLayout(this);
        pick.setGravity(Gravity.CENTER);
        npH = picker(23);
        npM = picker(59);
        npS = picker(59);
        pick.addView(pickCol(npH, "jam"));
        pick.addView(pickCol(npM, "menit"));
        pick.addView(pickCol(npS, "detik"));
        setup.addView(pick, lp(WC, WC, 0));

        TextView pl = tv("Pilihan cepat", 13, cSub);
        pl.setPadding(0, dp(18), 0, dp(8));
        setup.addView(pl);
        int[] mins = {1, 3, 5, 10, 15, 30};
        LinearLayout pr1 = new LinearLayout(this);
        LinearLayout pr2 = new LinearLayout(this);
        for (int i = 0; i < mins.length; i++) {
            final int m = mins[i];
            TextView c = tv(m + " menit", 14, cText);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(14), dp(10), dp(14), dp(10));
            c.setBackground(ripple(cSurf2, 20));
            c.setOnClickListener(x -> { setPickers(m * 60000L); st.timer.duration = m * 60000L; st.save(); });
            LinearLayout.LayoutParams l = lp(dp(96), WC, 0);
            l.setMargins(dp(4), dp(4), dp(4), dp(4));
            (i < 3 ? pr1 : pr2).addView(c, l);
        }
        setup.addView(pr1);
        setup.addView(pr2);
        tmSetup = setup;
        mid.addView(setup, new FrameLayout.LayoutParams(MP, MP));

        // --- tampilan berjalan
        LinearLayout run = new LinearLayout(this);
        run.setOrientation(LinearLayout.VERTICAL);
        run.setGravity(Gravity.CENTER);
        tmBig = new TextView(this);
        tmBig.setTextColor(cText);
        tmBig.setGravity(Gravity.CENTER);
        tmBig.setTypeface(digits());
        tmBig.setFontFeatureSettings("tnum");
        tmBig.setMaxLines(1);
        tmBig.setAutoSizeTextTypeUniformWithConfiguration(28, 150, 2, TypedValue.COMPLEX_UNIT_SP);
        run.addView(tmBig, lp(MP, dp(140), 0));
        tmProg = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        tmProg.setMax(10000);
        tmProg.setProgressTintList(ColorStateList.valueOf(cAcc));
        tmProg.setProgressBackgroundTintList(ColorStateList.valueOf(cSurf2));
        LinearLayout.LayoutParams pl2 = lp(MP, dp(10), 0);
        pl2.setMargins(dp(24), dp(8), dp(24), dp(8));
        run.addView(tmProg, pl2);
        tmStatus = tv("", 16, cSub);
        tmStatus.setGravity(Gravity.CENTER);
        run.addView(tmStatus, lp(MP, WC, 0));
        tmRun = run;
        mid.addView(run, new FrameLayout.LayoutParams(MP, MP));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(0, dp(12), 0, 0);
        tLeft = btn("Batal", cSurf2, cText);
        tMid = btn("+1 menit", cSurf2, cText);
        tRight = btn("Mulai", cAcc, cOnAcc);
        LinearLayout.LayoutParams l1 = lp(0, dp(64), 1); l1.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams l2 = lp(0, dp(64), 1); l2.setMargins(0, 0, dp(8), 0);
        bar.addView(tLeft, l1);
        bar.addView(tMid, l2);
        bar.addView(tRight, lp(0, dp(64), 1.4f));
        v.addView(bar, lp(MP, WC, 0));

        tRight.setOnClickListener(x -> { buzz(x); timerMain(); });
        tLeft.setOnClickListener(x -> {
            buzz(x);
            if (st.timer.state == Store.Tm.DONE) { st.timerStart(); }
            else st.timerCancel();
            st.changed();
        });
        tMid.setOnClickListener(x -> { buzz(x); st.timerAdd(60000); st.changed(); });

        setPickers(st.timer.duration);
        return v;
    }

    NumberPicker picker(int max) {
        NumberPicker n = new NumberPicker(this);
        n.setMinValue(0);
        n.setMaxValue(max);
        n.setFormatter(i -> String.format(Locale.US, "%02d", i));
        n.setOnValueChangedListener((p, o, nv) -> {
            st.timer.duration = pickerMs();
            if (st.timer.state == Store.Tm.IDLE) st.timer.remain = st.timer.duration;
            shownTmState = -1;
        });
        if (Build.VERSION.SDK_INT >= 29) {
            n.setTextColor(cText);
            n.setTextSize(dp(26));
        }
        return n;
    }

    View pickCol(NumberPicker n, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(10), 0, dp(10), 0);
        c.addView(n);
        TextView t = tv(label, 13, cSub);
        c.addView(t);
        return c;
    }

    long pickerMs() {
        return npH.getValue() * 3600000L + npM.getValue() * 60000L + npS.getValue() * 1000L;
    }

    void setPickers(long ms) {
        long s = ms / 1000;
        npH.setValue((int) Math.min(23, s / 3600));
        npM.setValue((int) ((s / 60) % 60));
        npS.setValue((int) (s % 60));
        shownTmState = -1;
    }

    void timerMain() {
        Store.Tm t = st.timer;
        switch (t.state) {
            case Store.Tm.IDLE:
                t.duration = pickerMs();
                if (t.duration <= 0) { Toast.makeText(this, "Atur waktunya dulu", Toast.LENGTH_SHORT).show(); return; }
                st.timerStart();
                break;
            case Store.Tm.RUNNING: st.timerPause(); break;
            case Store.Tm.PAUSED: st.timerStart(); break;
            case Store.Tm.DONE:
                Notif.stopAlarm(this);
                t.state = Store.Tm.IDLE;
                t.remain = t.duration;
                break;
        }
        st.changed();
    }

    void updateTimer() {
        Store.Tm t = st.timer;
        // Jaga-jaga: jika waktu habis tapi alarm belum datang
        if (t.state == Store.Tm.RUNNING && t.remaining() <= 0) {
            TimerReceiver.cancel(this);
            TimerReceiver.finish(this);
            return;
        }
        if (shownTmState != t.state) {
            shownTmState = t.state;
            boolean idle = t.state == Store.Tm.IDLE;
            tmSetup.setVisibility(idle ? View.VISIBLE : View.GONE);
            tmRun.setVisibility(idle ? View.GONE : View.VISIBLE);
            switch (t.state) {
                case Store.Tm.IDLE:
                    tLeft.setVisibility(View.INVISIBLE);
                    tMid.setVisibility(View.INVISIBLE);
                    styleBtn(tRight, "Mulai", cAcc, cOnAcc, pickerMs() > 0);
                    break;
                case Store.Tm.RUNNING:
                    tLeft.setVisibility(View.VISIBLE); tMid.setVisibility(View.VISIBLE);
                    styleBtn(tLeft, "Batal", cSurf2, cRed, true);
                    styleBtn(tMid, "+1 menit", cSurf2, cText, true);
                    styleBtn(tRight, "Jeda", cOrange, 0xFF000000, true);
                    tmStatus.setText("Timer " + Store.fmtShort(t.duration));
                    break;
                case Store.Tm.PAUSED:
                    tLeft.setVisibility(View.VISIBLE); tMid.setVisibility(View.VISIBLE);
                    styleBtn(tLeft, "Batal", cSurf2, cRed, true);
                    styleBtn(tMid, "+1 menit", cSurf2, cText, true);
                    styleBtn(tRight, "Lanjut", cAcc, cOnAcc, true);
                    tmStatus.setText("Dijeda");
                    break;
                case Store.Tm.DONE:
                    tLeft.setVisibility(View.VISIBLE); tMid.setVisibility(View.INVISIBLE);
                    styleBtn(tLeft, "Ulangi", cSurf2, cText, true);
                    styleBtn(tRight, "Stop", cRed, 0xFFFFFFFF, true);
                    tmStatus.setText("Waktu habis!");
                    break;
            }
        }
        if (t.state != Store.Tm.IDLE) {
            long r = t.remaining();
            tmBig.setText(Store.fmtDown(r));
            if (t.state == Store.Tm.DONE) {
                boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
                tmBig.setTextColor(blink ? cRed : cText);
            } else tmBig.setTextColor(t.state == Store.Tm.PAUSED ? cOrange : cText);
            long d = Math.max(1, t.duration);
            tmProg.setMax((int) Math.min(Integer.MAX_VALUE, Math.max(d, r)));
            tmProg.setProgress((int) r);
        }
    }

    // ---------------------------------------------------------------------
    View buildHistoryPage() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), 0, dp(16), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(4), 0, dp(10));
        histTitle = tv("Riwayat", 15, cSub);
        top.addView(histTitle, lp(0, WC, 1));
        TextView exp = smallBtn("Ekspor CSV");
        exp.setOnClickListener(x -> exportCsv());
        top.addView(exp);
        TextView clr = smallBtn("Hapus semua");
        clr.setTextColor(cRed);
        clr.setOnClickListener(x -> {
            if (st.history.isEmpty()) return;
            new AlertDialog.Builder(this).setTitle("Hapus semua riwayat?")
                    .setMessage(st.history.size() + " sesi akan dihapus permanen.")
                    .setPositiveButton("Hapus", (d, w) -> { st.history.clear(); st.changed(); })
                    .setNegativeButton("Batal", null).show();
        });
        LinearLayout.LayoutParams cl = lp(WC, WC, 0);
        cl.setMargins(dp(8), 0, 0, 0);
        top.addView(clr, cl);
        v.addView(top, lp(MP, WC, 0));

        FrameLayout f = new FrameLayout(this);
        histList = new ListView(this);
        histList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        histList.setDividerHeight(dp(8));
        histList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        histAdapter = new HistAdapter();
        histList.setAdapter(histAdapter);
        histList.setOnItemClickListener((p, view, pos, id) -> showDetail(st.history.get(pos)));
        f.addView(histList, new FrameLayout.LayoutParams(MP, MP));
        histEmpty = tv("Belum ada riwayat.\n\nSesi stopwatch otomatis tersimpan di sini\nsetiap kali Om menekan Reset.", 15, cSub);
        histEmpty.setGravity(Gravity.CENTER);
        f.addView(histEmpty, new FrameLayout.LayoutParams(MP, MP));
        v.addView(f, lp(MP, 0, 1));
        return v;
    }

    TextView smallBtn(String s) {
        TextView t = tv(s, 14, cAcc);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(ripple(cSurf2, 16));
        return t;
    }

    void refreshHistory() {
        histTitle.setText(st.history.size() + " sesi tersimpan");
        histEmpty.setVisibility(st.history.isEmpty() ? View.VISIBLE : View.GONE);
        histAdapter.notifyDataSetChanged();
    }

    static final SimpleDateFormat DF = new SimpleDateFormat("EEE, d MMM yyyy  HH:mm", new Locale("id", "ID"));
    static final SimpleDateFormat TF = new SimpleDateFormat("HH:mm:ss", Locale.US);

    class HistAdapter extends BaseAdapter {
        @Override public int getCount() { return st.history.size(); }
        @Override public Object getItem(int i) { return st.history.get(i); }
        @Override public long getItemId(int i) { return st.history.get(i).id; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout r;
            TextView name, total, sub, note;
            if (cv == null) {
                r = new LinearLayout(MainActivity.this);
                r.setOrientation(LinearLayout.VERTICAL);
                r.setPadding(dp(16), dp(12), dp(16), dp(12));
                r.setBackground(ripple(cSurf, 14));
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                name = tv("", 16, cText);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                total = tv("", 20, cText);
                total.setTypeface(digits());
                total.setFontFeatureSettings("tnum");
                row.addView(name, lp(0, WC, 1));
                row.addView(total, lp(WC, WC, 0));
                sub = tv("", 13, cSub);
                note = tv("", 13, cAcc);
                r.addView(row);
                r.addView(sub);
                r.addView(note);
                r.setTag(new TextView[]{name, total, sub, note});
            } else {
                r = (LinearLayout) cv;
            }
            TextView[] t = (TextView[]) r.getTag();
            Store.Hist x = st.history.get(i);
            t[0].setText((x.video != null && !x.video.isEmpty() ? "🎥 " : "") + x.name);
            t[1].setText(Store.fmt(x.total));
            t[2].setText(DF.format(new Date(x.startWall)) + "   •   " + (x.laps.isEmpty() ? "tanpa lap" : x.laps.size() + " lap"));
            t[3].setText(x.note);
            t[3].setVisibility(x.note == null || x.note.isEmpty() ? View.GONE : View.VISIBLE);
            return r;
        }
    }

    void showDetail(Store.Hist x) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(22), dp(16), dp(22), dp(4));

        TextView total = tv(Store.fmt(x.total), 40, cText);
        total.setTypeface(digits());
        total.setFontFeatureSettings("tnum");
        v.addView(total);
        TextView when = tv("Mulai " + DF.format(new Date(x.startWall)) + "\nSelesai " + DF.format(new Date(x.endWall)), 13, cSub);
        v.addView(when);
        if (x.note != null && !x.note.isEmpty()) {
            TextView n = tv(x.note, 14, cAcc);
            n.setPadding(0, dp(8), 0, 0);
            v.addView(n);
        }
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(0, dp(12), 0, dp(8));
        TextView ed = smallBtn("Ubah nama / catatan");
        TextView sh = smallBtn("Bagikan");
        acts.addView(ed);
        LinearLayout.LayoutParams sl = lp(WC, WC, 0);
        sl.setMargins(dp(8), 0, 0, 0);
        acts.addView(sh, sl);
        v.addView(acts);
        if (x.video != null && !x.video.isEmpty()) {
            TextView pl = smallBtn("▶  Putar video");
            LinearLayout.LayoutParams pll = lp(WC, WC, 0);
            pll.setMargins(0, 0, 0, dp(8));
            v.addView(pl, pll);
            pl.setOnClickListener(z -> {
                try {
                    Intent vi = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(x.video), "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(vi);
                } catch (Exception e) {
                    Toast.makeText(this, "Video tidak ditemukan (mungkin sudah dihapus dari Galeri)", Toast.LENGTH_LONG).show();
                }
            });
        }

        if (!x.laps.isEmpty()) {
            LinearLayout hd = lapRow("Lap", "Waktu lap", "Total", cSub, cSub, 13);
            hd.setPadding(0, dp(6), 0, dp(4));
            v.addView(hd);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            fillLaps(list, x.laps, x.total);
            v.addView(list);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(v);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(x.name)
                .setView(sc)
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Hapus", (dd, w) -> new AlertDialog.Builder(this).setTitle("Hapus sesi ini?")
                        .setPositiveButton("Hapus", (d2, w2) -> { st.history.remove(x); st.changed(); })
                        .setNegativeButton("Batal", null).show())
                .create();
        ed.setOnClickListener(z -> { d.dismiss(); editHist(x); });
        sh.setOnClickListener(z -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, histText(x));
            startActivity(Intent.createChooser(s, "Bagikan"));
        });
        d.show();
    }

    void editHist(Store.Hist x) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);
        EditText n = new EditText(this);
        n.setHint("Nama");
        n.setText(x.name);
        n.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText c = new EditText(this);
        c.setHint("Catatan (mis. cycle time line 530)");
        c.setText(x.note);
        c.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        v.addView(n);
        v.addView(c);
        new AlertDialog.Builder(this).setTitle("Ubah sesi").setView(v)
                .setPositiveButton("Simpan", (d, w) -> {
                    String nn = n.getText().toString().trim();
                    if (!nn.isEmpty()) x.name = nn;
                    x.note = c.getText().toString().trim();
                    st.changed();
                    showDetail(x);
                }).setNegativeButton("Batal", null).show();
    }

    String histText(Store.Hist x) {
        StringBuilder b = new StringBuilder();
        b.append(x.name).append('\n');
        b.append(DF.format(new Date(x.startWall))).append('\n');
        b.append("Total: ").append(Store.fmt(x.total)).append('\n');
        if (x.note != null && !x.note.isEmpty()) b.append("Catatan: ").append(x.note).append('\n');
        for (int i = 0; i < x.laps.size(); i++) {
            long sp = x.laps.get(i) - (i == 0 ? 0 : x.laps.get(i - 1));
            b.append("Lap ").append(i + 1).append(": ").append(Store.fmt(sp))
                    .append("  (").append(Store.fmt(x.laps.get(i))).append(")\n");
        }
        return b.toString();
    }

    // ---------- ekspor CSV ----------
    void exportCsv() {
        if (st.history.isEmpty()) { Toast.makeText(this, "Riwayat masih kosong", Toast.LENGTH_SHORT).show(); return; }
        String name = "riwayat_stopwatch_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".csv";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, REQ_EXPORT); }
        catch (Exception e) { Toast.makeText(this, "Tidak bisa membuka penyimpanan", Toast.LENGTH_LONG).show(); }
    }

    static String secs(long ms) {
        return String.format(Locale.US, "%.2f", ms / 1000.0).replace('.', ',');
    }

    static String q(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    String buildCsv() {
        SimpleDateFormat d = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
        StringBuilder b = new StringBuilder();
        b.append("sep=;\r\n");
        b.append("Sesi;Nama;Tanggal;Jam mulai;Jam selesai;Total;Total (detik);Lap ke;Waktu lap;Waktu lap (detik);Total saat lap;Catatan\r\n");
        int no = st.history.size();
        for (Store.Hist x : st.history) {
            String base = no + ";" + q(x.name) + ";" + d.format(new Date(x.startWall)) + ";"
                    + TF.format(new Date(x.startWall)) + ";" + TF.format(new Date(x.endWall)) + ";"
                    + q(Store.fmt(x.total)) + ";" + secs(x.total) + ";";
            if (x.laps.isEmpty()) {
                b.append(base).append(";;;;").append(q(x.note)).append("\r\n");
            } else {
                for (int i = 0; i < x.laps.size(); i++) {
                    long sp = x.laps.get(i) - (i == 0 ? 0 : x.laps.get(i - 1));
                    b.append(base).append(i + 1).append(';').append(q(Store.fmt(sp))).append(';')
                            .append(secs(sp)).append(';').append(q(Store.fmt(x.laps.get(i)))).append(';')
                            .append(q(x.note)).append("\r\n");
                }
            }
            no--;
        }
        return b.toString();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            os.write(buildCsv().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Riwayat berhasil diekspor", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal ekspor: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // =====================================================================
    //  Pengaturan
    // =====================================================================
    void showSettings() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(22), dp(8), dp(22), dp(8));

        v.addView(section("Tema"));
        RadioGroup gTheme = new RadioGroup(this);
        String[] th = {"Ikut sistem", "Terang", "Gelap", "Hitam pekat (AMOLED)"};
        for (int i = 0; i < th.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(th[i]);
            r.setId(100 + i);
            gTheme.addView(r);
        }
        gTheme.check(100 + st.theme);
        v.addView(gTheme);

        v.addView(section("Tombol volume (saat aplikasi terbuka)"));
        RadioGroup gVol = new RadioGroup(this);
        String[] vo = {"Volume Up = Lap,  Volume Down = Mulai/Jeda",
                "Volume Up = Mulai/Jeda,  Volume Down = Lap",
                "Mati (tombol volume normal)"};
        for (int i = 0; i < vo.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(vo[i]);
            r.setId(200 + i);
            gVol.addView(r);
        }
        gVol.check(200 + st.volMode);
        v.addView(gVol);

        v.addView(section("Suara saat Lap"));
        RadioGroup gVoice = new RadioGroup(this);
        String[] vl = {"Sebut waktu lap", "Sebut total waktu", "Sebut waktu lap + total", "Mati"};
        int[] vlMap = {1, 2, 3, 0};
        for (int i = 0; i < vl.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(vl[i]);
            r.setId(300 + vlMap[i]);
            gVoice.addView(r);
        }
        gVoice.check(300 + st.voiceLap);
        v.addView(gVoice);
        Switch sVPause = sw("Sebut total waktu saat Jeda", st.voicePause);
        v.addView(sVPause);
        TextView ll = tv("Bahasa suara", 14, cSub);
        ll.setPadding(0, dp(8), 0, 0);
        v.addView(ll);
        RadioGroup gLang = new RadioGroup(this);
        gLang.setOrientation(RadioGroup.HORIZONTAL);
        String[] lg = {"Indonesia", "English"};
        for (int i = 0; i < 2; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(lg[i]);
            r.setId(400 + i);
            gLang.addView(r);
        }
        gLang.check(400 + st.voiceLang);
        v.addView(gLang);
        TextView rl = tv("Kecepatan bicara", 14, cSub);
        rl.setPadding(0, dp(8), 0, 0);
        v.addView(rl);
        RadioGroup gRate = new RadioGroup(this);
        gRate.setOrientation(RadioGroup.HORIZONTAL);
        String[] rt = {"Pelan", "Normal", "Cepat", "Kilat"};
        for (int i = 0; i < 4; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(rt[i]);
            r.setId(500 + i);
            gRate.addView(r);
        }
        gRate.check(500 + st.voiceRate);
        v.addView(gRate);
        TextView test = smallBtn("Coba suara");
        LinearLayout.LayoutParams tl = lp(WC, WC, 0);
        tl.setMargins(0, dp(8), 0, 0);
        v.addView(test, tl);
        test.setOnClickListener(x -> {
            st.voiceLang = gLang.getCheckedRadioButtonId() - 400;
            st.voiceRate = gRate.getCheckedRadioButtonId() - 500;
            boolean en = st.voiceLang == 1;
            Speaker.speak(this, "Lap 3, " + Speaker.spoken(72340, en));
            h.postDelayed(this::checkVoiceLang, 1500);
        });

        v.addView(section("Lainnya"));
        Switch sKeep = sw("Layar tetap menyala saat berjalan", st.keepOn);
        Switch sHap = sw("Getar kecil saat tombol ditekan", st.haptic);
        Switch sHist = sw("Simpan ke Riwayat saat Reset", st.autoHistory);
        v.addView(sKeep);
        v.addView(sHap);
        v.addView(sHist);

        TextView ver = tv("Stopwatch v1.2", 12, cSub);
        ver.setPadding(0, dp(14), 0, 0);
        v.addView(ver);

        ScrollView sc = new ScrollView(this);
        sc.addView(v);
        new AlertDialog.Builder(this).setTitle("Pengaturan").setView(sc)
                .setPositiveButton("Simpan", (d, w) -> {
                    int nt = gTheme.getCheckedRadioButtonId() - 100;
                    boolean themeChanged = nt != st.theme;
                    st.theme = nt;
                    st.volMode = gVol.getCheckedRadioButtonId() - 200;
                    st.keepOn = sKeep.isChecked();
                    st.haptic = sHap.isChecked();
                    st.autoHistory = sHist.isChecked();
                    st.voiceLap = gVoice.getCheckedRadioButtonId() - 300;
                    st.voicePause = sVPause.isChecked();
                    st.voiceLang = gLang.getCheckedRadioButtonId() - 400;
                    st.voiceRate = gRate.getCheckedRadioButtonId() - 500;
                    if (st.voiceLap != 0 || st.voicePause) Speaker.init(this);
                    st.save();
                    keepOnApplied = !st.keepOn; // paksa terapkan ulang
                    if (themeChanged) recreate();
                }).setNegativeButton("Batal", null).show();
    }

    void checkVoiceLang() {
        if (Speaker.langStatus == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                || Speaker.langStatus == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            new AlertDialog.Builder(this).setTitle("Suara bahasa belum terpasang")
                    .setMessage("Mesin suara di HP belum punya bahasa ini. Pasang data suaranya sekarang?")
                    .setPositiveButton("Pasang", (d, w) -> {
                        try { startActivity(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                        catch (Exception e) { Toast.makeText(this, "Buka Pengaturan > Text-to-speech", Toast.LENGTH_LONG).show(); }
                    }).setNegativeButton("Nanti", null).show();
        }
    }

    TextView section(String s) {
        TextView t = tv(s, 14, cAcc);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    Switch sw(String s, boolean on) {
        Switch w = new Switch(this);
        w.setText(s);
        w.setChecked(on);
        w.setPadding(0, dp(8), 0, dp(8));
        w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return w;
    }

    // =====================================================================
    void updateTimes() {
        if (page == 0) updateStopwatch();
        else if (page == 1) updateTimer();
        else if (st.timer.state == Store.Tm.RUNNING && st.timer.remaining() <= 0) updateTimer();

        boolean want = st.keepOn && st.anyRunning();
        if (want != keepOnApplied) {
            keepOnApplied = want;
            if (want) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------- tombol volume ----------
    boolean isVol(int code) { return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN; }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (isVol(code) && st.volMode != 2 && page != 2) {
            boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
            boolean lapKey = (st.volMode == 0) == up;
            if (page == 1 && lapKey) return super.onKeyDown(code, e);
            if (e.getRepeatCount() == 0) {
                buzz(content);
                if (page == 0) { if (lapKey) swLap(); else swStartPause(); }
                else timerMain();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isVol(code) && st.volMode != 2 && page != 2) {
            boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
            boolean lapKey = (st.volMode == 0) == up;
            if (page == 1 && lapKey) return super.onKeyUp(code, e);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override
    public void onBackPressed() {
        if (page != 0) { showPage(0); return; }
        super.onBackPressed();
    }
}
