package id.bram.stopwatch;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;

/** Efek suara pendek yang dibuat sendiri (tanpa file unduhan). */
public class Beeper {
    public static final String[] NAMES = {"Bip", "Klik", "Ding", "Bip ganda"};
    private static SoundPool pool;
    private static final int[] ids = new int[4];
    private static final boolean[] loaded = new boolean[4];
    static final int RATE = 44100;

    static synchronized void init(Context c) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            for (int i = 0; i < 4; i++) if (ids[i] == id) loaded[i] = status == 0;
        });
        for (int i = 0; i < 4; i++) {
            try {
                File f = new File(c.getCacheDir(), "snd" + i + "_v1.wav");
                if (!f.exists()) writeWav(f, synth(i));
                ids[i] = pool.load(f.getAbsolutePath(), 1);
            } catch (Exception ignored) {}
        }
    }

    /** kind: 0 lap, 1 mulai (lebih tinggi), 2 jeda (lebih rendah) */
    public static void play(Context c, int kind) {
        init(c);
        int t = Math.max(0, Math.min(3, Store.get(c).beepType));
        if (!loaded[t]) return;
        float rate = kind == 1 ? 1.26f : kind == 2 ? 0.8f : 1f;
        pool.play(ids[t], 1f, 1f, 1, 0, rate);
    }

    // ---------- pembuat suara ----------
    static short[] synth(int type) {
        switch (type) {
            case 1: { // klik
                int n = (int) (RATE * 0.03);
                short[] s = new short[n];
                java.util.Random r = new java.util.Random(7);
                for (int i = 0; i < n; i++) {
                    double t = i / (double) RATE;
                    double env = Math.exp(-t * 180);
                    double v = 0.6 * Math.sin(2 * Math.PI * 2400 * t) + 0.4 * (r.nextDouble() * 2 - 1);
                    s[i] = (short) (v * env * 0.9 * 32767);
                }
                return s;
            }
            case 2: { // ding
                int n = (int) (RATE * 0.7);
                short[] s = new short[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) RATE;
                    double env = Math.exp(-t * 6) * Math.min(1, t * 400);
                    double v = 0.7 * Math.sin(2 * Math.PI * 1320 * t) + 0.25 * Math.sin(2 * Math.PI * 2640 * t)
                            + 0.1 * Math.sin(2 * Math.PI * 3960 * t);
                    s[i] = (short) (v * env * 0.8 * 32767);
                }
                return s;
            }
            case 3: { // bip ganda
                int one = (int) (RATE * 0.07), gap = (int) (RATE * 0.06);
                short[] s = new short[one * 2 + gap];
                for (int k = 0; k < 2; k++) {
                    int off = k * (one + gap);
                    for (int i = 0; i < one; i++) s[off + i] = tone(i, one, 1200);
                }
                return s;
            }
            default: { // bip
                int n = (int) (RATE * 0.12);
                short[] s = new short[n];
                for (int i = 0; i < n; i++) s[i] = tone(i, n, 1000);
                return s;
            }
        }
    }

    static short tone(int i, int n, double hz) {
        double t = i / (double) RATE;
        int fade = (int) (RATE * 0.006);
        double env = Math.min(1, Math.min(i / (double) fade, (n - i) / (double) fade));
        return (short) (Math.sin(2 * Math.PI * hz * t) * env * 0.8 * 32767);
    }

    static void writeWav(File f, short[] pcm) throws Exception {
        int dataLen = pcm.length * 2;
        byte[] b = new byte[44 + dataLen];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(dataLen);
        for (short v : pcm) bb.putShort(v);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }
}
