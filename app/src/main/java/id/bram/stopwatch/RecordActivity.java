package id.bram.stopwatch;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.effects.Frame;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Rekam video dengan angka stopwatch & lap tertempel di videonya. */
public class RecordActivity extends ComponentActivity implements Store.Listener {

    Store st;
    Store.Sw sw;
    final Handler ui = new Handler(Looper.getMainLooper());
    HandlerThread glThread;
    Handler glHandler;

    PreviewView pv;
    TextView bLap, bRec, bStart, tName, tInfo, bPos, bSize, bRot;
    VideoCapture<Recorder> videoCapture;
    OverlayEffect overlay;
    Recording recording;
    ProcessCameraProvider provider;

    volatile Store.Sw snap;
    volatile boolean recOn = false;
    volatile long recStartRt = 0;
    volatile int ovPos, ovSize;
    boolean stopping = false;

    // Paint untuk overlay (dipakai di thread kamera)
    final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pRec = new Paint(Paint.ANTI_ALIAS_FLAG);
    final SimpleDateFormat DT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);

    static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    @Override
    protected void onCreate(Bundle b) {
        st = Store.get(this);
        setRequestedOrientation(st.recLandscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sw = st.activeSw();
        ovPos = st.ovPos;
        ovSize = st.ovSize;
        setupPaints();
        buildUi();
        updateSnap();
        if (st.voiceLap != 0 || st.voicePause) Speaker.init(this);

        glThread = new HandlerThread("overlay");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        if (needPerms().length > 0) requestPermissions(needPerms(), 5);
        else startCamera();
    }

    String[] needPerms() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) l.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) l.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return l.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Izin kamera diperlukan untuk merekam", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Tanpa izin mikrofon, video direkam tanpa suara", Toast.LENGTH_LONG).show();
        startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Store.listener = this;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Store.listener == this) Store.listener = null;
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // keluar aplikasi saat merekam: hentikan & simpan
        if (recording != null) stopRecording();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (overlay != null) overlay.close();
        if (glThread != null) glThread.quitSafely();
    }

    @Override
    public void onStoreChanged() {
        if (st.byId(sw.id) == null) sw = st.activeSw();
        updateSnap();
        refreshButtons();
    }

    void updateSnap() { snap = sw.copy(); }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            updateInfo();
            ui.postDelayed(this, 200);
        }
    };

    // ------------------------------------------------------------------
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView btn(String s, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setBg(t, bg);
        return t;
    }

    void setBg(TextView t, int bg) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(30));
        GradientDrawable m = new GradientDrawable();
        m.setColor(Color.WHITE);
        m.setCornerRadius(dp(30));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), g, m));
    }

    TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x66000000);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), 0x55FFFFFF);
        t.setBackground(g);
        return t;
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pv = new PreviewView(this);
        pv.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        pv.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(pv, new FrameLayout.LayoutParams(MP, MP));

        // atas
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        top.setBackgroundColor(0x66000000);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = chip("←  Kembali");
        back.setOnClickListener(v -> onBackPressed());
        r1.addView(back);
        tName = new TextView(this);
        tName.setTextColor(Color.WHITE);
        tName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tName.setGravity(Gravity.END);
        tName.setSingleLine(true);
        r1.addView(tName, new LinearLayout.LayoutParams(0, WC, 1));
        top.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setPadding(0, dp(8), 0, 0);
        bPos = chip("");
        bSize = chip("");
        bRot = chip("");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WC, WC);
        cl.setMargins(0, 0, dp(8), 0);
        r2.addView(bPos, cl);
        r2.addView(bSize, new LinearLayout.LayoutParams(cl));
        r2.addView(bRot, new LinearLayout.LayoutParams(cl));
        top.addView(r2);
        tInfo = new TextView(this);
        tInfo.setTextColor(0xFFDDDDDD);
        tInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tInfo.setPadding(0, dp(6), 0, 0);
        top.addView(tInfo);
        root.addView(top, new FrameLayout.LayoutParams(MP, WC, Gravity.TOP));

        bPos.setOnClickListener(v -> {
            ovPos = (ovPos + 1) % 4; st.ovPos = ovPos; st.save(); refreshButtons();
        });
        bSize.setOnClickListener(v -> {
            ovSize = (ovSize + 1) % 3; st.ovSize = ovSize; st.save(); refreshButtons();
        });
        bRot.setOnClickListener(v -> {
            if (recording != null) { Toast.makeText(this, "Hentikan rekaman dulu", Toast.LENGTH_SHORT).show(); return; }
            st.recLandscape = !st.recLandscape; st.save();
            recreate();
        });

        // bawah
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(18));
        bar.setBackgroundColor(0x66000000);
        bLap = btn("Lap", 0xFF3A3F47, Color.WHITE);
        bRec = btn("● Rekam", 0xFFD32F2F, Color.WHITE);
        bStart = btn("Mulai", 0xFF1E88E5, Color.WHITE);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(60), 1);
        l1.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(60), 1.3f);
        l2.setMargins(0, 0, dp(8), 0);
        bar.addView(bLap, l1);
        bar.addView(bRec, l2);
        bar.addView(bStart, new LinearLayout.LayoutParams(0, dp(60), 1));
        root.addView(bar, new FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM));

        bLap.setOnClickListener(v -> { buzz(v); doLap(); });
        bStart.setOnClickListener(v -> { buzz(v); doStartPause(); });
        bRec.setOnClickListener(v -> { buzz(v); if (recording == null) startRecording(); else stopRecording(); });

        setContentView(root);
        refreshButtons();
    }

    void buzz(View v) {
        if (st.haptic && v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    void refreshButtons() {
        String[] pos = {"Kiri bawah", "Kanan bawah", "Kiri atas", "Kanan atas"};
        String[] size = {"Kecil", "Sedang", "Besar"};
        bPos.setText("Posisi: " + pos[ovPos]);
        bSize.setText("Ukuran: " + size[ovSize]);
        bRot.setText(st.recLandscape ? "Mode: Mendatar" : "Mode: Tegak");
        tName.setText(sw.name);
        boolean rec = recording != null;
        bRec.setText(rec ? "■ Stop" : "● Rekam");
        setBg(bRec, rec ? 0xFF555A62 : 0xFFD32F2F);
        bStart.setText(sw.running ? "Jeda" : (sw.elapsed() > 0 ? "Lanjut" : "Mulai"));
        setBg(bStart, sw.running ? 0xFFFB8C00 : 0xFF1E88E5);
        bLap.setEnabled(sw.running);
        bLap.setAlpha(sw.running ? 1f : 0.4f);
        bRot.setAlpha(rec ? 0.4f : 1f);
    }

    void updateInfo() {
        if (recording != null) {
            long t = android.os.SystemClock.elapsedRealtime() - recStartRt;
            tInfo.setText("● Merekam  " + Store.fmtShort(t) + "   •   Lap & Jeda tetap bisa ditekan");
            tInfo.setTextColor(0xFFFF6B6B);
        } else {
            tInfo.setText("Tekan Rekam: video & stopwatch mulai bersamaan. Angka di layar ikut terekam.");
            tInfo.setTextColor(0xFFDDDDDD);
        }
    }

    void doLap() {
        if (!sw.running) return;
        st.lap(sw);
        st.changed();
    }

    void doStartPause() {
        st.toggle(sw);
        st.changed();
    }

    // ------------------------------------------------------------------
    //  Kamera
    // ------------------------------------------------------------------
    void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                bindCamera();
            } catch (Exception e) {
                Toast.makeText(this, "Kamera gagal dibuka: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindCamera() {
        androidx.camera.core.resolutionselector.ResolutionSelector rs =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build();
        Preview preview = new Preview.Builder().setResolutionSelector(rs).build();
        preview.setSurfaceProvider(pv.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.HD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        overlay = new OverlayEffect(CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE, 0, glHandler,
                t -> ui.post(() -> Toast.makeText(this, "Overlay error: " + t.getMessage(), Toast.LENGTH_LONG).show()));
        overlay.setOnDrawListener(this::drawFrame);

        UseCaseGroup group = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture)
                .addEffect(overlay)
                .build();
        try {
            provider.unbindAll();
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group);
        } catch (Exception e) {
            Toast.makeText(this, "Kamera gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void startRecording() {
        if (videoCapture == null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safe = sw.name.replaceAll("[^A-Za-z0-9_-]+", "_");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "Stopwatch_" + safe + "_" + stamp);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Stopwatch");
        MediaStoreOutputOptions opts = new MediaStoreOutputOptions.Builder(getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build();

        PendingRecording pr = videoCapture.getOutput().prepareRecording(this, opts);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try { pr = pr.withAudioEnabled(); } catch (SecurityException ignored) {}
        }
        stopping = false;
        recording = pr.start(ContextCompat.getMainExecutor(this), this::onRecordEvent);
        recStartRt = android.os.SystemClock.elapsedRealtime();
        recOn = true;
        if (!sw.running) { st.start(sw); st.changed(); }
        refreshButtons();
    }

    void stopRecording() {
        if (recording == null || stopping) return;
        stopping = true;
        if (sw.running) { st.pause(sw); }
        updateSnap();
        recording.stop();
    }

    void onRecordEvent(VideoRecordEvent e) {
        if (!(e instanceof VideoRecordEvent.Finalize)) return;
        VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) e;
        recording = null;
        recOn = false;
        Uri uri = f.getOutputResults().getOutputUri();
        boolean ok = uri != null && !Uri.EMPTY.equals(uri);
        if (sw.running) st.pause(sw);
        String u = ok ? uri.toString() : null;
        if (sw.acc > 0) st.reset(sw, u, true);
        st.changed();
        if (ok) Toast.makeText(this, "Video tersimpan di Galeri (Movies/Stopwatch) dan masuk Riwayat", Toast.LENGTH_LONG).show();
        else Toast.makeText(this, "Rekaman gagal disimpan (kode " + f.getError() + ")", Toast.LENGTH_LONG).show();
        refreshButtons();
    }

    // ------------------------------------------------------------------
    //  Gambar angka ke setiap frame video
    // ------------------------------------------------------------------
    void setupPaints() {
        Typeface tf = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        pBig.setTypeface(tf);
        pBig.setColor(Color.WHITE);
        pBig.setFontFeatureSettings("tnum");
        pBig.setShadowLayer(4, 0, 2, 0xAA000000);
        pTxt.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        pTxt.setColor(0xFFE8E8E8);
        pTxt.setFontFeatureSettings("tnum");
        pTxt.setShadowLayer(3, 0, 1, 0xAA000000);
        pBg.setColor(0x8C000000);
        pRec.setColor(0xFFFF3B30);
    }

    boolean drawFrame(Frame frame) {
        Canvas c = frame.getOverlayCanvas();
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        int bw = frame.getSize().getWidth(), bh = frame.getSize().getHeight();
        int r = ((frame.getRotationDegrees() % 360) + 360) % 360;
        float uw = (r % 180 == 0) ? bw : bh;
        float uh = (r % 180 == 0) ? bh : bw;
        Matrix m = new Matrix();
        m.setRotate(-r);
        RectF rr = new RectF(0, 0, uw, uh);
        m.mapRect(rr);
        m.postTranslate(-rr.left, -rr.top);
        c.save();
        c.concat(m);
        try { drawOverlay(c, uw, uh); } catch (Exception ignored) {}
        c.restore();
        return true;
    }

    void drawOverlay(Canvas c, float W, float H) {
        Store.Sw s = snap;
        if (s == null) return;
        // Batasi ke area 16:9 yang benar-benar masuk ke video (sisanya dipotong oleh perekam)
        float target = W > H ? 16f / 9f : 9f / 16f;
        float cur = W / H;
        if (cur > target * 1.01f) {
            float nw = H * target;
            c.translate((W - nw) / 2f, 0);
            W = nw;
        } else if (cur < target * 0.99f) {
            float nh = W / target;
            c.translate(0, (H - nh) / 2f);
            H = nh;
        }
        float[] scales = {0.75f, 1f, 1.35f};
        float k = Math.min(W, H) / 720f * scales[ovSize];
        long el = s.elapsed();
        int n = s.laps.size();
        long last = n == 0 ? 0 : s.laps.get(n - 1);

        float bigSz = 64 * k, txtSz = 24 * k, lineGap = 8 * k, pad = 16 * k, margin = 20 * k;
        pBig.setTextSize(bigSz);
        pTxt.setTextSize(txtSz);
        pBig.setColor(s.running ? Color.WHITE : 0xFFFFB74D);

        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> cols = new java.util.ArrayList<>();
        if (el > 0) { lines.add("Lap " + (n + 1) + "   " + Store.fmt(el - last)); cols.add(0xFFFFFFFF); }
        long fastest = Long.MAX_VALUE, slowest = -1;
        if (n >= 2) for (int i = 0; i < n; i++) {
            long sp = s.laps.get(i) - (i == 0 ? 0 : s.laps.get(i - 1));
            fastest = Math.min(fastest, sp); slowest = Math.max(slowest, sp);
        }
        for (int i = n - 1; i >= Math.max(0, n - 3); i--) {
            long sp = s.laps.get(i) - (i == 0 ? 0 : s.laps.get(i - 1));
            int col = 0xFFD0D0D0;
            if (n >= 2 && sp == fastest) col = 0xFF6EE7A0;
            else if (n >= 2 && sp == slowest) col = 0xFFFF8A80;
            lines.add("Lap " + (i + 1) + "   " + Store.fmt(sp));
            cols.add(col);
        }
        String head = s.name + "   " + DT.format(new Date());
        String big = Store.fmt(el);

        float w = Math.max(pBig.measureText(big), pTxt.measureText(head));
        for (String l : lines) w = Math.max(w, pTxt.measureText(l));
        float recW = recOn ? txtSz * 1.1f : 0;
        w += recW;
        float hgt = txtSz + lineGap + bigSz + lines.size() * (txtSz + lineGap) + lineGap;
        float bwid = w + pad * 2, bhgt = hgt + pad * 2;

        float x = (ovPos == 0 || ovPos == 2) ? margin : W - margin - bwid;
        float y = (ovPos == 2 || ovPos == 3) ? margin : H - margin - bhgt;
        c.drawRoundRect(new RectF(x, y, x + bwid, y + bhgt), 14 * k, 14 * k, pBg);

        float tx = x + pad, ty = y + pad;
        // judul + titik merah saat merekam
        if (recOn) {
            boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
            if (blink) c.drawCircle(tx + txtSz * 0.35f, ty + txtSz * 0.6f, txtSz * 0.3f, pRec);
        }
        pTxt.setColor(0xFFBDBDBD);
        c.drawText(head, tx + recW, ty + txtSz * 0.9f, pTxt);
        ty += txtSz + lineGap;
        c.drawText(big, tx, ty + bigSz * 0.85f, pBig);
        ty += bigSz + lineGap;
        for (int i = 0; i < lines.size(); i++) {
            pTxt.setColor(cols.get(i));
            c.drawText(lines.get(i), tx, ty + txtSz * 0.9f, pTxt);
            ty += txtSz + lineGap;
        }
    }

    // ------------------------------------------------------------------
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && st.volMode != 2) {
            if (e.getRepeatCount() == 0) {
                boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
                boolean lapKey = (st.volMode == 0) == up;
                buzz(pv);
                if (lapKey) doLap(); else doStartPause();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && st.volMode != 2) return true;
        return super.onKeyUp(code, e);
    }

    @Override
    public void onBackPressed() {
        if (recording != null) {
            new android.app.AlertDialog.Builder(this).setTitle("Sedang merekam")
                    .setMessage("Hentikan rekaman dan simpan videonya?")
                    .setPositiveButton("Stop & simpan", (d, w) -> stopRecording())
                    .setNegativeButton("Lanjut merekam", null).show();
            return;
        }
        super.onBackPressed();
    }
}
