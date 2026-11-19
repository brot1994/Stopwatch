package id.bram.stopwatch;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.Effect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tambah musik latar ke video hasil rekaman (video asli tetap disimpan). */
public class MixActivity extends Activity {

    static final int REQ_MUSIC = 31;
    static final int BG = 0xFF121418, SURF = 0xFF1D2026, SURF2 = 0xFF2A2E36, TXT = 0xFFF1F3F5, SUB = 0xFF9AA1AC, ACC = 0xFF4FA3FF;
    static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    Store st;
    Store.Hist hist;
    final Handler ui = new Handler(Looper.getMainLooper());

    String musicUri, musicTitle, musicCredit = "";
    long musicDurMs = 0;

    TextView tMusic, tCredit, tStart, tMVol, tOVol, bListen, bMake, tStatus, bPick;
    SeekBar sStart, sMVol, sOVol;
    ProgressBar prog;
    LinearLayout opts;

    MediaPlayer player;
    Transformer transformer;
    File outFile;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int c, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(r));
        return g;
    }

    TextView tv(String s, float sp, int col) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        return t;
    }

    TextView btn(String s, int bg, int fg) {
        TextView t = tv(s, 16, fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(bg, 26), round(Color.WHITE, 26)));
        t.setPadding(dp(16), 0, dp(16), 0);
        return t;
    }

    SeekBar seek(int max, int val) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(val);
        s.setProgressTintList(ColorStateList.valueOf(ACC));
        s.setThumbTintList(ColorStateList.valueOf(ACC));
        s.setPadding(dp(8), dp(10), dp(8), dp(10));
        return s;
    }

    @Override
    protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(b);
        st = Store.get(this);
        long id = getIntent().getLongExtra("hist", 0);
        for (Store.Hist h : st.history) if (h.id == id) hist = h;
        if (hist == null || hist.video == null || hist.video.isEmpty()) { finish(); return; }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(10), dp(18), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = btn("←", SURF2, TXT);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(WC, dp(44)));
        TextView title = tv("Tambah musik ke video", 20, TXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        ScrollView sc = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(14), 0, dp(14));
        sc.addView(v);
        root.addView(sc, new LinearLayout.LayoutParams(MP, 0, 1));

        TextView vid = tv("Video: " + hist.name + "   (" + Store.fmt(hist.total) + ")", 14, SUB);
        v.addView(vid);

        tMusic = tv("Belum ada musik dipilih", 17, TXT);
        tMusic.setPadding(0, dp(18), 0, dp(4));
        v.addView(tMusic);
        tCredit = tv("", 12, SUB);
        v.addView(tCredit);
        bPick = btn("🎵  Pilih musik", ACC, 0xFF0B1220);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(MP, dp(52));
        pl.setMargins(0, dp(12), 0, dp(8));
        v.addView(bPick, pl);
        bPick.setOnClickListener(x -> { stopListen(); startActivityForResult(new Intent(this, MusicActivity.class), REQ_MUSIC); });

        opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setVisibility(View.GONE);
        v.addView(opts);

        tStart = tv("", 14, TXT);
        tStart.setPadding(0, dp(16), 0, 0);
        opts.addView(tStart);
        sStart = seek(0, 0);
        opts.addView(sStart);
        bListen = btn("▶  Dengar dari titik ini", SURF2, TXT);
        opts.addView(bListen, new LinearLayout.LayoutParams(MP, dp(46)));
        bListen.setOnClickListener(x -> { if (player != null) stopListen(); else listen(); });

        tMVol = tv("", 14, TXT);
        tMVol.setPadding(0, dp(18), 0, 0);
        opts.addView(tMVol);
        sMVol = seek(100, 50);
        opts.addView(sMVol);

        tOVol = tv("", 14, TXT);
        tOVol.setPadding(0, dp(10), 0, 0);
        opts.addView(tOVol);
        sOVol = seek(100, 100);
        opts.addView(sOVol);
        TextView hint = tv("Tips: musik 30–50% supaya suara penyebut lap & suara asli tetap terdengar.", 12, SUB);
        opts.addView(hint);

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                labels();
                if (s == sMVol && player != null) player.setVolume(p / 100f, p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { if (s == sStart && player != null) { stopListen(); listen(); } }
        };
        sStart.setOnSeekBarChangeListener(l);
        sMVol.setOnSeekBarChangeListener(l);
        sOVol.setOnSeekBarChangeListener(l);

        tStatus = tv("", 14, SUB);
        tStatus.setPadding(0, dp(8), 0, dp(4));
        root.addView(tStatus);
        prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(100);
        prog.setProgressTintList(ColorStateList.valueOf(ACC));
        prog.setVisibility(View.GONE);
        root.addView(prog, new LinearLayout.LayoutParams(MP, dp(8)));
        bMake = btn("Buat video dengan musik", 0xFF2E7D32, Color.WHITE);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(MP, dp(56));
        ml.setMargins(0, dp(10), 0, 0);
        root.addView(bMake, ml);
        bMake.setEnabled(false);
        bMake.setAlpha(0.4f);
        bMake.setOnClickListener(x -> make());

        setContentView(root);
        labels();
    }

    void labels() {
        if (sStart == null) return;
        tStart.setText("Musik mulai dari detik ke:  " + Store.fmtShort(sStart.getProgress() * 1000L)
                + (musicDurMs > 0 ? "   (lagu " + Store.fmtShort(musicDurMs) + ")" : ""));
        tMVol.setText("Volume musik:  " + sMVol.getProgress() + "%");
        tOVol.setText("Volume suara asli video:  " + sOVol.getProgress() + "%");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_MUSIC || res != RESULT_OK || data == null) return;
        musicUri = data.getStringExtra("uri");
        musicTitle = data.getStringExtra("title");
        musicCredit = data.getStringExtra("credit");
        if (musicCredit == null) musicCredit = "";
        musicDurMs = 0;
        try {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            r.setDataSource(this, Uri.parse(musicUri));
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            musicDurMs = d == null ? 0 : Long.parseLong(d);
            r.release();
        } catch (Exception ignored) {}
        tMusic.setText("🎵  " + musicTitle);
        tCredit.setText(musicCredit);
        bPick.setText("Ganti musik");
        opts.setVisibility(View.VISIBLE);
        sStart.setMax((int) Math.max(0, musicDurMs / 1000 - 1));
        sStart.setProgress(0);
        bMake.setEnabled(true);
        bMake.setAlpha(1f);
        labels();
    }

    void listen() {
        if (musicUri == null) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(this, Uri.parse(musicUri));
            player.prepare();
            player.seekTo(sStart.getProgress() * 1000);
            float vol = sMVol.getProgress() / 100f;
            player.setVolume(vol, vol);
            player.setOnCompletionListener(mp -> stopListen());
            player.start();
            bListen.setText("■  Berhenti");
        } catch (Exception e) {
            Toast.makeText(this, "Musik gagal diputar", Toast.LENGTH_SHORT).show();
            stopListen();
        }
    }

    void stopListen() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (bListen != null) bListen.setText("▶  Dengar dari titik ini");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListen();
    }

    static AudioProcessor volume(float v) {
        ChannelMixingAudioProcessor p = new ChannelMixingAudioProcessor();
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(v));
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v));
        return p;
    }

    // ---------- proses ----------
    void make() {
        if (musicUri == null || transformer != null) return;
        stopListen();
        float mVol = sMVol.getProgress() / 100f;
        float oVol = sOVol.getProgress() / 100f;
        long startMs = sStart.getProgress() * 1000L;

        List<AudioProcessor> vp = new ArrayList<>();
        vp.add(volume(oVol));
        EditedMediaItem videoItem = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(hist.video)))
                .setEffects(new Effects(vp, Collections.<Effect>emptyList()))
                .build();

        MediaItem mItem = new MediaItem.Builder()
                .setUri(Uri.parse(musicUri))
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).build())
                .build();
        List<AudioProcessor> mp = new ArrayList<>();
        mp.add(volume(mVol));
        EditedMediaItem musicItem = new EditedMediaItem.Builder(mItem)
                .setRemoveVideo(true)
                .setEffects(new Effects(mp, Collections.<Effect>emptyList()))
                .build();

        List<EditedMediaItem> ml = new ArrayList<>();
        ml.add(musicItem);
        Composition comp = new Composition.Builder(
                new EditedMediaItemSequence(videoItem),
                new EditedMediaItemSequence(ml, /* isLooping= */ true))
                .experimentalSetForceAudioTrack(true)
                .build();

        outFile = new File(getCacheDir(), "mix_" + System.currentTimeMillis() + ".mp4");
        transformer = new Transformer.Builder(this)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition c, ExportResult r) { done(); }
                    @Override public void onError(Composition c, ExportResult r, ExportException e) { failed(e); }
                })
                .build();
        setBusy(true);
        tStatus.setText("Memproses video… jangan tutup layar ini");
        try {
            transformer.start(comp, outFile.getAbsolutePath());
            ui.post(progressTick);
        } catch (Exception e) {
            failed(e);
        }
    }

    final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (transformer == null) return;
            ProgressHolder ph = new ProgressHolder();
            if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) {
                prog.setProgress(ph.progress);
                tStatus.setText("Memproses video… " + ph.progress + "%");
            }
            ui.postDelayed(this, 300);
        }
    };

    void setBusy(boolean b) {
        prog.setVisibility(b ? View.VISIBLE : View.GONE);
        prog.setProgress(0);
        bMake.setEnabled(!b);
        bMake.setAlpha(b ? 0.4f : 1f);
        bPick.setEnabled(!b);
        opts.setAlpha(b ? 0.5f : 1f);
    }

    String originalName() {
        try (Cursor c = getContentResolver().query(Uri.parse(hist.video),
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n.replaceAll("\\.mp4$", "");
            }
        } catch (Exception ignored) {}
        return "Stopwatch_" + System.currentTimeMillis();
    }

    void done() {
        transformer = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, originalName() + "_musik.mp4");
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Stopwatch");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }
            Uri u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new Exception("Galeri menolak file");
            try (OutputStream o = getContentResolver().openOutputStream(u);
                 FileInputStream in = new FileInputStream(outFile)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(u, done, null, null);
            }
            outFile.delete();
            hist.videoMusic = u.toString();
            if (!musicCredit.isEmpty()) {
                String c = "Musik: " + musicCredit;
                if (hist.note == null || hist.note.isEmpty()) hist.note = c;
                else if (!hist.note.contains(c)) hist.note = hist.note + "\n" + c;
            }
            st.changed();
            setBusy(false);
            prog.setVisibility(View.VISIBLE);
            prog.setProgress(100);
            tStatus.setText("Selesai! Video dengan musik tersimpan di Galeri (Movies/Stopwatch).");
            bMake.setText("▶  Putar hasilnya");
            bMake.setOnClickListener(x -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (Exception e) {
                    Toast.makeText(this, "Tidak ada pemutar video", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            failed(e);
        }
    }

    void failed(Exception e) {
        transformer = null;
        if (outFile != null) outFile.delete();
        setBusy(false);
        tStatus.setText("Gagal: " + e.getMessage());
    }

    @Override
    public void onBackPressed() {
        if (transformer != null) {
            new android.app.AlertDialog.Builder(this).setTitle("Batalkan proses?")
                    .setPositiveButton("Batalkan", (d, w) -> {
                        try { transformer.cancel(); } catch (Exception ignored) {}
                        transformer = null;
                        if (outFile != null) outFile.delete();
                        finish();
                    })
                    .setNegativeButton("Lanjutkan", null).show();
            return;
        }
        super.onBackPressed();
    }
}
