package id.bram.stopwatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pilih musik: cari & dengarkan dari Jamendo, atau ambil file dari HP. */
public class MusicActivity extends Activity {

    static final int REQ_FILE = 21;
    static final int BG = 0xFF121418, SURF = 0xFF1D2026, SURF2 = 0xFF2A2E36, TXT = 0xFFF1F3F5, SUB = 0xFF9AA1AC, ACC = 0xFF4FA3FF;
    static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String[] GENRES = {"Semua", "pop", "rock", "electronic", "acoustic", "hiphop", "jazz", "ambient",
            "cinematic", "chillout", "dance", "piano", "lounge", "funk", "classical", "reggae"};

    Store st;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> imgCache = new LruCache<>(80);

    EditText q;
    String tag = "";
    final ArrayList<TextView> genreChips = new ArrayList<>();
    final ArrayList<Jamendo.Track> tracks = new ArrayList<>();
    TrackAdapter adapter;
    TextView status, more;
    ProgressBar loading;
    boolean busy = false, endReached = false;
    int searchGen = 0;

    MediaPlayer player;
    String playingId = null;
    boolean preparing = false;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int c, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(r));
        return g;
    }

    RippleDrawable ripple(int c, float r) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(c, r), round(Color.WHITE, r));
    }

    TextView tv(String s, float sp, int col) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        return t;
    }

    TextView chip(String s, int bg, int fg) {
        TextView t = tv(s, 14, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(ripple(bg, 18));
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(b);
        st = Store.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = chip("←", SURF2, TXT);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = tv("Pilih musik", 20, TXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, WC, 1));
        TextView key = chip("Kunci", SURF2, SUB);
        key.setOnClickListener(v -> askKey(false));
        top.addView(key);
        root.addView(top);

        TextView file = chip("📁  Pilih lagu dari file HP", SURF2, TXT);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(MP, dp(48));
        fl.setMargins(0, dp(12), 0, dp(4));
        root.addView(file, fl);
        file.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
            startActivityForResult(i, REQ_FILE);
        });

        TextView jl = tv("Musik gratis dari Jamendo  (ketuk ▶ untuk dengar dulu)", 13, SUB);
        jl.setPadding(0, dp(10), 0, dp(6));
        root.addView(jl);

        LinearLayout sr = new LinearLayout(this);
        q = new EditText(this);
        q.setHint("Cari judul, artis, atau suasana (mis. happy)");
        q.setHintTextColor(SUB);
        q.setTextColor(TXT);
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setBackground(round(SURF, 12));
        q.setPadding(dp(12), dp(10), dp(12), dp(10));
        q.setOnEditorActionListener((v, a, e) -> { search(); return true; });
        sr.addView(q, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView go = chip("Cari", ACC, 0xFF0B1220);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(WC, dp(46));
        gl.setMargins(dp(8), 0, 0, 0);
        sr.addView(go, gl);
        go.setOnClickListener(v -> search());
        root.addView(sr);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout gr = new LinearLayout(this);
        gr.setPadding(0, dp(8), 0, dp(8));
        for (String g : GENRES) {
            TextView c = chip(g.equals("Semua") ? g : cap(g), SURF2, TXT);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            c.setOnClickListener(v -> { tag = g.equals("Semua") ? "" : g; markGenres(); search(); });
            c.setTag(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WC, WC);
            l.setMargins(0, 0, dp(6), 0);
            gr.addView(c, l);
            genreChips.add(c);
        }
        hs.addView(gr);
        root.addView(hs);

        FrameLayout box = new FrameLayout(this);
        ListView lv = new ListView(this);
        lv.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lv.setDividerHeight(dp(6));
        lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        more = tv("Muat lebih banyak", 14, ACC);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(14), 0, dp(14));
        more.setOnClickListener(v -> load(false));
        lv.addFooterView(more);
        adapter = new TrackAdapter();
        lv.setAdapter(adapter);
        box.addView(lv, new FrameLayout.LayoutParams(MP, MP));
        status = tv("", 14, SUB);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(20), 0, dp(20), 0);
        box.addView(status, new FrameLayout.LayoutParams(MP, MP));
        loading = new ProgressBar(this);
        box.addView(loading, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        loading.setVisibility(View.GONE);
        root.addView(box, new LinearLayout.LayoutParams(MP, 0, 1));

        TextView credit = tv("Musik Jamendo berlisensi Creative Commons (bebas untuk pemakaian non-komersial).", 11, SUB);
        credit.setPadding(0, dp(6), 0, 0);
        root.addView(credit);

        setContentView(root);
        markGenres();

        if (st.jamendoKey == null || st.jamendoKey.trim().isEmpty()) askKey(true);
        else search();
    }

    static String cap(String s) { return s.substring(0, 1).toUpperCase() + s.substring(1); }

    void markGenres() {
        for (TextView c : genreChips) {
            String g = (String) c.getTag();
            boolean on = (g.equals("Semua") && tag.isEmpty()) || g.equals(tag);
            c.setBackground(ripple(on ? ACC : SURF2, 18));
            c.setTextColor(on ? 0xFF0B1220 : TXT);
        }
    }

    void askKey(boolean first) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(22), dp(8), dp(22), 0);
        TextView info = tv("Untuk mencari musik dari Jamendo perlu \"Client ID\" gratis:\n\n"
                + "1. Buka devportal.jamendo.com dan daftar / masuk\n"
                + "2. Pilih \"Create an app\" (nama bebas)\n"
                + "3. Salin Client ID dan tempel di bawah\n\n"
                + "Cukup sekali saja.", 14, SUB);
        v.addView(info);
        EditText e = new EditText(this);
        e.setHint("Client ID Jamendo");
        e.setSingleLine(true);
        e.setText(st.jamendoKey);
        v.addView(e);
        new AlertDialog.Builder(this).setTitle("Kunci Jamendo").setView(v)
                .setPositiveButton("Simpan", (d, w) -> {
                    st.jamendoKey = e.getText().toString().trim();
                    st.save();
                    search();
                })
                .setNeutralButton("Buka situs", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://devportal.jamendo.com"))); }
                    catch (Exception ignored) {}
                })
                .setNegativeButton("Batal", (d, w) -> {
                    if (first) status.setText("Isi Kunci Jamendo dulu (tombol \"Kunci\" di kanan atas),\natau pilih lagu dari file HP.");
                })
                .show();
    }

    void search() {
        endReached = false;
        tracks.clear();
        adapter.notifyDataSetChanged();
        load(true);
    }

    void load(boolean fresh) {
        if (st.jamendoKey == null || st.jamendoKey.trim().isEmpty()) {
            status.setText("Isi Kunci Jamendo dulu (tombol \"Kunci\" di kanan atas),\natau pilih lagu dari file HP.");
            more.setVisibility(View.GONE);
            return;
        }
        if (busy && !fresh) return;
        busy = true;
        final int gen = ++searchGen;
        final String query = q.getText().toString();
        final String tg = tag;
        final int off = tracks.size();
        final String key = st.jamendoKey.trim();
        loading.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        status.setText("");
        more.setText("Memuat…");
        net.execute(() -> {
            try {
                ArrayList<Jamendo.Track> r = Jamendo.search(key, query, tg, off);
                ui.post(() -> {
                    if (gen != searchGen) return;
                    busy = false;
                    loading.setVisibility(View.GONE);
                    tracks.addAll(r);
                    endReached = r.size() < 30;
                    adapter.notifyDataSetChanged();
                    more.setText("Muat lebih banyak");
                    more.setVisibility(endReached ? View.GONE : View.VISIBLE);
                    status.setText(tracks.isEmpty() ? "Tidak ada lagu ditemukan." : "");
                });
            } catch (Exception ex) {
                ui.post(() -> {
                    if (gen != searchGen) return;
                    busy = false;
                    loading.setVisibility(View.GONE);
                    more.setText("Muat lebih banyak");
                    if (tracks.isEmpty()) {
                        status.setText("Gagal memuat musik:\n" + ex.getMessage() + "\n\nCek internet atau Kunci Jamendo.");
                        more.setVisibility(View.GONE);
                    } else Toast.makeText(this, "Gagal: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ---------- dengar dulu ----------
    void togglePlay(Jamendo.Track t) {
        if (t.id.equals(playingId)) { stopPlay(); return; }
        stopPlay();
        playingId = t.id;
        preparing = true;
        adapter.notifyDataSetChanged();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(t.audio);
            player.setOnPreparedListener(mp -> { preparing = false; mp.start(); adapter.notifyDataSetChanged(); });
            player.setOnCompletionListener(mp -> stopPlay());
            player.setOnErrorListener((mp, w, x) -> {
                Toast.makeText(this, "Lagu gagal diputar", Toast.LENGTH_SHORT).show();
                stopPlay();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            stopPlay();
        }
    }

    void stopPlay() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playingId = null;
        preparing = false;
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlay();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    // ---------- pakai ----------
    void use(Jamendo.Track t) {
        stopPlay();
        File dir = new File(getFilesDir(), "musik");
        dir.mkdirs();
        File f = new File(dir, "jamendo_" + t.id + ".mp3");
        String title = t.name + " — " + t.artist;
        String credit = "\"" + t.name + "\" oleh " + t.artist + " (Jamendo" + (t.license.isEmpty() ? "" : ", " + t.license) + ")";
        if (f.exists() && f.length() > 0) { finishWith(Uri.fromFile(f).toString(), title, credit); return; }

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(22), dp(16), dp(22), dp(8));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        TextView pt = tv("Mengunduh…", 14, SUB);
        v.addView(pt);
        v.addView(pb);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(t.name).setView(v).setCancelable(false).create();
        d.show();
        net.execute(() -> {
            try {
                Jamendo.download(t.download, f, pc -> ui.post(() -> { pb.setProgress(pc); pt.setText("Mengunduh… " + pc + "%"); }));
                ui.post(() -> { d.dismiss(); finishWith(Uri.fromFile(f).toString(), title, credit); });
            } catch (Exception e) {
                f.delete();
                ui.post(() -> { d.dismiss(); Toast.makeText(this, "Gagal mengunduh: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    void finishWith(String uri, String title, String credit) {
        setResult(RESULT_OK, new Intent().putExtra("uri", uri).putExtra("title", title).putExtra("credit", credit));
        finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = "Lagu dari HP";
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        // salin ke penyimpanan aplikasi supaya tetap bisa dibaca nanti
        File dir = new File(getFilesDir(), "musik");
        dir.mkdirs();
        File f = new File(dir, "hp_" + Math.abs(u.toString().hashCode()) + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        final String nm = name;
        net.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(u);
                 java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                ui.post(() -> finishWith(Uri.fromFile(f).toString(), nm, ""));
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Gagal membaca lagu: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---------- gambar sampul ----------
    void loadImg(ImageView iv, String url) {
        iv.setTag(url);
        Bitmap c = url == null ? null : imgCache.get(url);
        if (c != null) { iv.setImageBitmap(c); return; }
        iv.setImageDrawable(round(SURF2, 8));
        if (url == null || url.isEmpty()) return;
        net.execute(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(10000);
                Bitmap bm;
                try (InputStream in = con.getInputStream()) { bm = BitmapFactory.decodeStream(in); }
                if (bm == null) return;
                imgCache.put(url, bm);
                ui.post(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); });
            } catch (Exception ignored) {}
        });
    }

    class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }
        @Override public Object getItem(int i) { return tracks.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout r;
            if (cv == null) {
                r = new LinearLayout(MusicActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(10), dp(10), dp(10), dp(10));
                r.setBackground(round(SURF, 12));
                ImageView img = new ImageView(MusicActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setClipToOutline(true);
                img.setBackground(round(SURF2, 8));
                r.addView(img, new LinearLayout.LayoutParams(dp(52), dp(52)));
                LinearLayout txt = new LinearLayout(MusicActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(dp(12), 0, dp(8), 0);
                TextView n = tv("", 15, TXT);
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                TextView a = tv("", 13, SUB);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(n);
                txt.addView(a);
                r.addView(txt, new LinearLayout.LayoutParams(0, WC, 1));
                TextView play = chip("▶", SURF2, TXT);
                play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                r.addView(play, new LinearLayout.LayoutParams(dp(48), dp(40)));
                TextView use = chip("Pakai", ACC, 0xFF0B1220);
                LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(WC, dp(40));
                ul.setMargins(dp(6), 0, 0, 0);
                r.addView(use, ul);
                r.setTag(new View[]{img, n, a, play, use});
            } else r = (LinearLayout) cv;
            View[] v = (View[]) r.getTag();
            Jamendo.Track t = tracks.get(i);
            loadImg((ImageView) v[0], t.image);
            ((TextView) v[1]).setText(t.name);
            ((TextView) v[2]).setText(t.artist + "  •  " + Store.fmtShort(t.duration * 1000L));
            boolean on = t.id.equals(playingId);
            TextView play = (TextView) v[3];
            play.setText(on ? (preparing ? "…" : "■") : "▶");
            play.setBackground(ripple(on ? 0xFFFB8C00 : SURF2, 18));
            play.setOnClickListener(x -> togglePlay(t));
            v[4].setOnClickListener(x -> use(t));
            return r;
        }
    }
}
