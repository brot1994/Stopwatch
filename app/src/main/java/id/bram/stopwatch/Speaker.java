package id.bram.stopwatch;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Menyebutkan waktu dengan suara (Text-to-Speech bawaan Android). */
public class Speaker {
    private static TextToSpeech tts;
    private static boolean ready = false;
    private static String pending = null;
    private static String langSet = null;
    public static int langStatus = TextToSpeech.SUCCESS;

    static void init(Context c) {
        if (tts != null) return;
        Context app = c.getApplicationContext();
        tts = new TextToSpeech(app, status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (!ready) return;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            applySettings(app);
            if (pending != null) { say(pending); pending = null; }
        });
    }

    static void applySettings(Context c) {
        if (tts == null || !ready) return;
        Store st = Store.get(c);
        String want = st.voiceLang == 1 ? "en" : "id";
        if (!want.equals(langSet)) {
            Locale loc = st.voiceLang == 1 ? Locale.US : new Locale("id", "ID");
            langStatus = tts.setLanguage(loc);
            langSet = want;
        }
        float[] rates = {0.8f, 1.0f, 1.3f, 1.6f};
        tts.setSpeechRate(rates[Math.max(0, Math.min(3, st.voiceRate))]);
    }

    private static void say(String text) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sw");
    }

    public static void speak(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        init(c);
        if (!ready) { pending = text; return; }
        applySettings(c);
        say(text);
    }

    /** Waktu dalam kata-kata yang enak didengar, mis. "1 menit 5,20 detik". */
    public static String spoken(long ms, boolean en) {
        long cs = (ms / 10) % 100;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000) % 60;
        long h = ms / 3600000;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(en ? (h == 1 ? " hour " : " hours ") : " jam ");
        if (m > 0) b.append(m).append(en ? (m == 1 ? " minute " : " minutes ") : " menit ");
        String frac = String.format(Locale.US, "%02d", cs);
        if (en) b.append(s).append('.').append(frac).append(" seconds");
        else b.append(s).append(',').append(frac).append(" detik");
        return b.toString();
    }

    /** Dipanggil setelah lap dicatat. */
    public static void onLap(Context c, Store.Sw s) {
        Store st = Store.get(c);
        if (st.voiceLap == 0 || s.laps.isEmpty()) return;
        boolean en = st.voiceLang == 1;
        int n = s.laps.size();
        long total = s.laps.get(n - 1);
        long split = total - (n >= 2 ? s.laps.get(n - 2) : 0);
        String t;
        if (st.voiceLap == 1) t = "Lap " + n + ", " + spoken(split, en);
        else if (st.voiceLap == 2) t = (en ? "Lap " + n + ", total " : "Lap " + n + ", total ") + spoken(total, en);
        else t = "Lap " + n + ", " + spoken(split, en) + (en ? ". Total " : ". Total ") + spoken(total, en);
        speak(c, t);
    }

    public static void onPause(Context c, Store.Sw s) {
        Store st = Store.get(c);
        if (!st.voicePause) return;
        boolean en = st.voiceLang == 1;
        speak(c, (en ? "Stopped, " : "Berhenti, ") + spoken(s.elapsed(), en));
    }
}
