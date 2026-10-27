package my.rafhan.app.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lirik berpenanda masa (format LRC) dari LRCLIB (https://lrclib.net, percuma, tanpa API key).
 * Dipakai oleh kad Now Playing dan screensaver. Hasil disimpan dalam memori (30 lagu terakhir);
 * kalau rangkaian gagal, cuba semula selepas 30 saat.
 */
final class LyricsProvider {

    /** Senarai baris lirik, diisih ikut masa. */
    static final class Lyrics {
        final long[] times;
        final String[] texts;

        Lyrics(long[] times, String[] texts) {
            this.times = times;
            this.texts = texts;
        }

        boolean isEmpty() {
            return times.length == 0;
        }

        /** Indeks baris yang sedang dinyanyikan pada kedudukan {@code posMs}, atau -1 kalau belum mula. */
        int indexAt(long posMs) {
            int lo = 0, hi = times.length - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] <= posMs) {
                    ans = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return ans;
        }
    }

    /** Tiada lirik berpenanda masa untuk lagu ini (disimpan supaya tak dicari berulang). */
    static final Lyrics NONE = new Lyrics(new long[0], new String[0]);

    /** Tajuk dan artis yang sudah dibersihkan, serta kunci cache. */
    static final class Query {
        final String track, artist, key;

        Query(String track, String artist) {
            this.track = track;
            this.artist = artist;
            this.key = (track + "|" + artist).toLowerCase(Locale.ROOT);
        }
    }

    interface Callback {
        /** Dipanggil pada thread utama bila lirik (atau {@link #NONE}) sudah ada. */
        void onResult(String key, Lyrics lyrics);
    }

    private static final String BASE = "https://lrclib.net/api/";
    private static final String UA = "MiTV-Launcher/1.0 (https://github.com/iskandarkhairul00-ai/MiTV-Debloat)";
    private static final long RETRY_AFTER_MS = 30_000;

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Lyrics> CACHE = new LinkedHashMap<String, Lyrics>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lyrics> e) {
            return size() > 30;
        }
    };
    private static final Set<String> INFLIGHT = new HashSet<>();
    private static final Map<String, Long> FAILED = new HashMap<>();

    private LyricsProvider() {
    }

    // ------------------------------------------------------------------ public

    static Lyrics cached(String key) {
        synchronized (LOCK) {
            return CACHE.get(key);
        }
    }

    /** Null kalau tiada tajuk. */
    static Query query(String title, String artist) {
        if (title == null || title.trim().isEmpty()) return null;
        String t = title.trim();
        String a = artist == null ? "" : artist.trim();
        // "Artis - Tajuk" (gaya YouTube) bila artis kosong, atau bila tajuk bermula dengan nama artis.
        int dash = t.indexOf(" - ");
        if (dash > 0) {
            if (a.isEmpty()) {
                a = t.substring(0, dash);
                t = t.substring(dash + 3);
            } else if (t.regionMatches(true, 0, a, 0, a.length()) && dash == a.length()) {
                t = t.substring(dash + 3);
            }
        }
        t = cleanTitle(t);
        a = cleanArtist(a);
        if (t.isEmpty()) return null;
        return new Query(t, a);
    }

    /**
     * Cari lirik di thread {@code worker}. Tiada apa-apa berlaku kalau lagu itu sedang dicari atau
     * baru gagal. Kalau sudah ada dalam cache, hasil dihantar terus ke thread utama.
     */
    static void fetch(Executor worker, String title, String artist, long durationMs, Callback cb) {
        Query q = query(title, artist);
        if (q == null) return;
        synchronized (LOCK) {
            Lyrics hit = CACHE.get(q.key);
            if (hit != null) {
                MAIN.post(() -> cb.onResult(q.key, hit));
                return;
            }
            if (INFLIGHT.contains(q.key)) return;
            Long failed = FAILED.get(q.key);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return;
            INFLIGHT.add(q.key);
        }
        worker.execute(() -> {
            Lyrics result = null;
            try {
                result = load(q, durationMs);
            } catch (Exception ignored) {
                // rangkaian / pelayan bermasalah: jangan cache, cuba lagi nanti
            }
            Lyrics fin = result;
            synchronized (LOCK) {
                INFLIGHT.remove(q.key);
                if (fin != null) {
                    CACHE.put(q.key, fin);
                    FAILED.remove(q.key);
                } else {
                    FAILED.put(q.key, System.currentTimeMillis());
                }
            }
            if (fin != null) MAIN.post(() -> cb.onResult(q.key, fin));
        });
    }

    // ------------------------------------------------------------------ network

    /** Lyrics (mungkin {@link #NONE}); lempar IOException bila rangkaian/pelayan gagal. */
    private static Lyrics load(Query q, long durationMs) throws Exception {
        long sec = Math.round(durationMs / 1000.0);

        // Sumber tambahan (port dari bitchordtv). Kegagalan satu sumber tak hentikan yang lain.
        Lyrics extra = null;
        try {
            extra = kugou(q, sec);
        } catch (Exception ignored) {
        }
        if (extra == null) {
            try {
                extra = lyricsPlus(q, sec);
            } catch (Exception ignored) {
            }
        }
        if (extra != null) return extra;

        // LRCLIB. 1. Padanan tepat
        StringBuilder u = new StringBuilder(BASE).append("get?track_name=").append(enc(q.track));
        if (!q.artist.isEmpty()) u.append("&artist_name=").append(enc(q.artist));
        if (sec > 0) u.append("&duration=").append(sec);
        String body = http(u.toString());
        if (body != null) {
            Lyrics l = fromObject(new JSONObject(body));
            if (l != null) return l;
        }

        // 2. Carian ikut tajuk + artis
        u = new StringBuilder(BASE).append("search?track_name=").append(enc(q.track));
        if (!q.artist.isEmpty()) u.append("&artist_name=").append(enc(q.artist));
        Lyrics l = bestOf(http(u.toString()), sec);
        if (l != null) return l;

        // 3. Carian bebas
        String free = q.artist.isEmpty() ? q.track : q.artist + " " + q.track;
        l = bestOf(http(BASE + "search?q=" + enc(free)), sec);
        return l != null ? l : NONE;
    }

    // ------------------------------------------------------------------ KuGou

    private static final Pattern KUGOU_STAMPED = Pattern.compile("^\\[\\d{2}:\\d{2}\\.\\d{2,3}\\].*");
    private static final Pattern KUGOU_CREDIT = Pattern.compile(".+\\][^\\[]+[:\uFF1A].+");
    private static final Pattern KUGOU_STAMP_ANY = Pattern.compile("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]");
    private static final Pattern KUGOU_INSTRUMENTAL = Pattern.compile("\u7EAF\u97F3\u4E50.*\u6B23\u8D4F");

    private static String stripParenthetical(String s) {
        String r = s.replaceAll("[(\uFF08].*?[)\uFF09]", "").trim();
        return r.isEmpty() ? s : r;
    }

    /** Fail KuGou bermula dan berakhir dengan baris kredit ("\u4F5C\u8BCD: ...") yang bertanda masa; buang. */
    static String stripKugouCredits(String lrc) {
        List<String> lines = new ArrayList<>();
        for (String l : lrc.split("\\r?\\n")) if (KUGOU_STAMPED.matcher(l).matches()) lines.add(l);
        if (lines.isEmpty()) return "";
        int head = 0;
        for (int i = Math.min(30, lines.size() - 1); i >= 0; i--) {
            if (KUGOU_CREDIT.matcher(lines.get(i)).matches()) {
                head = i + 1;
                break;
            }
        }
        List<String> body = lines.subList(head, lines.size());
        int tail = 0;
        for (int i = 0; i <= Math.min(30, body.size() - 1); i++) {
            if (KUGOU_CREDIT.matcher(body.get(body.size() - 1 - i)).matches()) {
                tail = i + 1;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.size() - tail; i++) sb.append(body.get(i)).append('\n');
        return sb.toString();
    }

    private static boolean isKugouInstrumental(String lrc) {
        boolean any = false;
        for (String l : lrc.split("\\r?\\n")) {
            String t = KUGOU_STAMP_ANY.matcher(l).replaceAll("").trim();
            if (t.isEmpty()) continue;
            any = true;
            if (!KUGOU_INSTRUMENTAL.matcher(t).find()) return false;
        }
        return any;
    }

    /** null = tiada; lempar kalau rangkaian gagal. */
    private static Lyrics kugou(Query q, long sec) throws Exception {
        String keyword = stripParenthetical(q.track)
                + (q.artist.isEmpty() ? "" : " - " + stripParenthetical(q.artist));

        // Cari lagu, ambil cincang (hash) yang tempohnya hampir sama.
        String songsBody = http("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0"
                + "&pagesize=8&showtype=0&keyword=" + enc(keyword));
        List<String> hashes = new ArrayList<>();
        if (songsBody != null) {
            JSONObject data = new JSONObject(songsBody).optJSONObject("data");
            JSONArray info = data == null ? null : data.optJSONArray("info");
            List<JSONObject> cands = new ArrayList<>();
            for (int i = 0; info != null && i < info.length(); i++) {
                JSONObject o = info.optJSONObject(i);
                if (o == null || o.optString("hash").isEmpty()) continue;
                long d = o.has("duration") ? o.optLong("duration", -1) : -1;
                if (sec <= 0 || Math.abs(d - sec) <= 8) cands.add(o);
            }
            final long target = sec;
            Collections.sort(cands, (a, b) -> Long.compare(
                    Math.abs(a.optLong("duration", 0) - target),
                    Math.abs(b.optLong("duration", 0) - target)));
            for (JSONObject o : cands) hashes.add(o.optString("hash"));
        }

        JSONObject candidate = null;
        for (String hash : hashes) {
            String b = http("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=" + enc(hash));
            candidate = firstCandidate(b);
            if (candidate != null) break;
        }
        if (candidate == null) {
            String b = http("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + enc(keyword)
                    + (sec > 0 ? "&duration=" + (sec * 1000) : ""));
            candidate = firstCandidate(b);
        }
        if (candidate == null) return null;

        String dl = http("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1"
                + "&id=" + enc(candidate.optString("id")) + "&accesskey=" + enc(candidate.optString("accesskey")));
        if (dl == null) return null;
        String content = new JSONObject(dl).optString("content", "");
        if (content.isEmpty()) return null;
        String lrc = new String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), "UTF-8");
        String stripped = stripKugouCredits(lrc);
        if (stripped.isEmpty() || isKugouInstrumental(stripped)) return null;
        Lyrics l = parseLrc(stripped);
        return l.isEmpty() ? null : l;
    }

    private static JSONObject firstCandidate(String body) throws Exception {
        if (body == null) return null;
        JSONArray arr = new JSONObject(body).optJSONArray("candidates");
        return arr == null || arr.length() == 0 ? null : arr.optJSONObject(0);
    }

    // ------------------------------------------------------------------ LyricsPlus

    private static final String[] PLUS_MIRRORS = {
            "https://lyricsplus.prjktla.my.id", "https://lyricsplus.binimum.org"};

    /** Baris sahaja (masa mula + teks); suku kata diabaikan. null = tiada. */
    private static Lyrics lyricsPlus(Query q, long sec) throws Exception {
        for (String host : PLUS_MIRRORS) {
            try {
                StringBuilder u = new StringBuilder(host).append("/v2/lyrics/get?title=").append(enc(q.track))
                        .append("&artist=").append(enc(q.artist));
                if (sec > 0) u.append("&duration=").append(sec);
                String body = http(u.toString());
                if (body == null) continue;
                JSONArray arr = new JSONObject(body).optJSONArray("lyrics");
                if (arr == null) continue;
                List<long[]> idx = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null || !o.has("time")) continue;
                    String text = o.optString("text", "").trim();
                    if (text.isEmpty()) {
                        JSONArray syl = o.optJSONArray("syllabus");
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; syl != null && k < syl.length(); k++) {
                            JSONObject w = syl.optJSONObject(k);
                            if (w != null) sb.append(w.optString("text", ""));
                        }
                        text = sb.toString().trim();
                    }
                    if (text.isEmpty()) continue;
                    texts.add(text);
                    idx.add(new long[]{o.optLong("time"), texts.size() - 1});
                }
                if (idx.isEmpty()) continue;
                Collections.sort(idx, (a, b) -> Long.compare(a[0], b[0]));
                long[] times = new long[idx.size()];
                String[] out = new String[idx.size()];
                for (int i = 0; i < times.length; i++) {
                    times[i] = idx.get(i)[0];
                    out[i] = texts.get((int) idx.get(i)[1]);
                }
                return new Lyrics(times, out);
            } catch (Exception ignored) {
                // cuba mirror seterusnya
            }
        }
        return null;
    }

    private static Lyrics bestOf(String body, long durationSec) throws Exception {
        if (body == null) return null;
        JSONArray arr = new JSONArray(body);
        JSONObject best = null;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.isNull("syncedLyrics") || o.optString("syncedLyrics").trim().isEmpty()) continue;
            long diff = durationSec > 0 && o.has("duration")
                    ? Math.abs(Math.round(o.optDouble("duration")) - durationSec) : 0;
            if (diff < bestDiff) {
                best = o;
                bestDiff = diff;
            }
        }
        // Versi yang panjangnya jauh beza biasanya lagu lain / versi lain: lirik tak akan sepadan.
        if (best == null || (durationSec > 0 && bestDiff > 15)) return null;
        return fromObject(best);
    }

    private static Lyrics fromObject(JSONObject o) {
        if (o.isNull("syncedLyrics")) return null;
        String lrc = o.optString("syncedLyrics", "");
        if (lrc.trim().isEmpty()) return null;
        Lyrics l = parseLrc(lrc);
        return l.isEmpty() ? null : l;
    }

    /** null = 404 (tiada); lempar IOException untuk ralat lain. */
    private static String http(String url) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0 && out.size() < (2 << 20); ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ parsing

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TAG = Pattern.compile("<\\d+:\\d+(?:[.:]\\d+)?>");

    static Lyrics parseLrc(String lrc) {
        List<long[]> idx = new ArrayList<>(); // {masa, indeks teks}
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("[")) continue;
            Matcher m = STAMP.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String f = m.group(3);
                if (f != null) {
                    ms = Long.parseLong(f);
                    for (int k = f.length(); k < 3; k++) ms *= 10;
                }
                stamps.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (stamps.isEmpty()) continue; // [ar:..], [ti:..] dan sebagainya
            String text = WORD_TAG.matcher(line.substring(end)).replaceAll("").trim();
            texts.add(text);
            for (long t : stamps) idx.add(new long[]{t, texts.size() - 1});
        }
        Collections.sort(idx, (a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[idx.size()];
        String[] out = new String[idx.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = idx.get(i)[0];
            out[i] = texts.get((int) idx.get(i)[1]);
        }
        return new Lyrics(times, out);
    }

    // ------------------------------------------------------------------ cleaning

    private static final Pattern NOISE_BRACKETS = Pattern.compile(
            "\\s*[(\\[][^)\\]]*(official|lyric|video|audio|visuali[sz]er|\\bmv\\b|\\bhd\\b|\\b4k\\b|remaster|karaoke)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);

    static String cleanTitle(String t) {
        t = NOISE_BRACKETS.matcher(t).replaceAll("");
        t = t.replaceAll("\\s*[|\\uFF5C].*$", "");
        return t.trim();
    }

    static String cleanArtist(String a) {
        a = a.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)\\s*vevo$", "");
        return a.trim();
    }
}
