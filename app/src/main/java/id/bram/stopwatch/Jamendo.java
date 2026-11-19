package id.bram.stopwatch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

/** Pencarian musik bebas royalti dari Jamendo (api.jamendo.com). */
public class Jamendo {

    public static class Track {
        public String id, name, artist, image, audio, download, license;
        public int duration;
    }

    public interface Progress { void on(int percent); }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return bo.toString("UTF-8");
    }

    /** query = teks bebas (boleh kosong), tag = genre (boleh kosong). */
    public static ArrayList<Track> search(String clientId, String query, String tag, int offset) throws Exception {
        StringBuilder u = new StringBuilder("https://api.jamendo.com/v3.0/tracks/?format=json&limit=30&imagesize=100&audioformat=mp32");
        u.append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"));
        u.append("&offset=").append(offset);
        if (query != null && !query.trim().isEmpty()) {
            u.append("&search=").append(URLEncoder.encode(query.trim(), "UTF-8"));
            u.append("&order=relevance");
        } else {
            u.append("&order=popularity_month");
        }
        if (tag != null && !tag.isEmpty()) u.append("&fuzzytags=").append(URLEncoder.encode(tag, "UTF-8"));

        JSONObject o = new JSONObject(get(u.toString()));
        JSONObject h = o.optJSONObject("headers");
        if (h != null && !"success".equals(h.optString("status"))) {
            String msg = h.optString("error_message", "Gagal");
            throw new Exception(msg.isEmpty() ? "Kunci Jamendo tidak valid" : msg);
        }
        JSONArray a = o.optJSONArray("results");
        ArrayList<Track> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.getJSONObject(i);
            Track x = new Track();
            x.id = t.optString("id");
            x.name = t.optString("name");
            x.artist = t.optString("artist_name");
            x.duration = t.optInt("duration");
            x.image = t.optString("image", t.optString("album_image"));
            x.audio = t.optString("audio");
            x.download = t.optBoolean("audiodownload_allowed", false) ? t.optString("audiodownload", x.audio) : x.audio;
            if (x.download == null || x.download.isEmpty()) x.download = x.audio;
            x.license = t.optString("license_ccurl");
            if (x.audio != null && !x.audio.isEmpty()) out.add(x);
        }
        return out;
    }

    public static void download(String url, File dest, Progress p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        long total = c.getContentLengthLong();
        File tmp = new File(dest.getPath() + ".part");
        try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[16384];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                done += n;
                if (total > 0 && p != null) {
                    int pc = (int) (done * 100 / total);
                    if (pc != last) { last = pc; p.on(pc); }
                }
            }
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new Exception("Gagal menyimpan file");
    }
}
