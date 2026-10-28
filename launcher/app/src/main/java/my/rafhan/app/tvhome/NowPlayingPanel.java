package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Now Playing" card: what another app is playing (cover, title, artist, app, progress) with
 * Mode, Previous, Play/Pause, Next and Open app buttons that MainActivity's remote focus can reach.
 *
 * This low-RAM TV won't let apps read other apps' media sessions (notification-listener access
 * is refused), so the data comes from outside the app:
 *  - USB debugging on: {@link MediaHelper} runs as the shell user over the loopback ADB
 *    connection ({@link AdbKeys}) and reports the session as JSON lines, cover art included;
 *  - otherwise: `dumpsys media_session` (DUMP is granted by build-and-install.sh), every few
 *    seconds while the home screen shows: app, title, artist, state, position; no cover/length.
 * Only while home is on screen (and not under the screensaver): `dumpsys` is skipped while no
 * audio plays, and the helper (~23 MB) runs only while a media session exists. Buttons send
 * media keys.
 */
final class NowPlayingPanel {
    /** Susunan butang kiri ke kanan: sebelumnya, main/jeda, seterusnya. */
    static final int PREV = 0, PLAY_PAUSE = 1, NEXT = 2, BUTTONS = 3;
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, LIVE = 0xFFFF5A52;
    /** Cover is a square this size, and sets the card's height. */
    private static final int COVER = 88;
    private static final long DUMPSYS_EVERY_MS = 4000;

    /** Lagu terakhir yang diketahui (dibaca juga oleh dashboard remote telefon). */
    static volatile Info last;

    interface Listener {
        /** The card appeared or disappeared. */
        void onShownChanged(boolean shown);
    }

    /** One snapshot of the session being shown (also read by the phone remote's dashboard). */
    static final class Info {
        String pkg, title, artist, artUri;
        int state, artId;
        long pos, updated, duration, actions;
        float speed = 1f;
    }

    private final Context ctx;
    private final LinearLayout card;
    private final Executor worker;
    private final Listener listener;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AdbKeys adb;
    private final AudioManager audio;

    private final ImageView cover;
    private final TextView source, title, artist, time;
    private final Bar bar;
    private final ImageView[] buttons = new ImageView[BUTTONS];

    private boolean enabled; // kad dipaparkan (walaupun tiada lagu: keadaan "Nothing playing")
    private Info info;
    private boolean started, shown, visibleOnScreen = true;
    private volatile AdbKeys.Hid helper;
    private int generation; // bumps on every start/stop, so stale threads don't touch the card
    private String coverKey;
    private Bitmap helperArt;
    private int helperArtId;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (needsTick()) main.postDelayed(this, 1000);
        }
    };

    /**
     * The cheap check: nothing to read while no audio plays and no card is up. Otherwise
     * `dumpsys media_session`, and when it finds a session (and ADB is up) the helper takes over
     * for cover art and progress.
     */
    private final Runnable dumpsysPoll = new Runnable() {
        @Override
        public void run() {
            if (!started || helper != null) return;
            if (visibleOnScreen) main.postDelayed(this, DUMPSYS_EVERY_MS);
            if (info == null && !audio.isMusicActive()) return;
            int gen = generation;
            worker.execute(() -> {
                Info i = readSession(ctx);
                main.post(() -> {
                    if (gen != generation || helper != null) return;
                    show(i);
                    if (i != null) startHelper();
                });
            });
        }
    };

    NowPlayingPanel(Context ctx, LinearLayout card, Executor worker, Listener listener) {
        this.ctx = ctx;
        this.card = card;
        this.worker = worker;
        this.listener = listener;
        dp = ctx.getResources().getDisplayMetrics().density;
        adb = AdbKeys.get(ctx);
        audio = ctx.getSystemService(AudioManager.class);

        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setVisibility(View.GONE);
        // The cover fills the card's left side edge to edge; the card's rounded outline clips it.
        card.setPadding(0, 0, px(12), 0);
        card.setClipToOutline(true);

        cover = new ImageView(ctx);
        cover.setBackgroundColor(0xFF1C1C1E);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setForeground(ctx.getDrawable(R.drawable.np_gloss)); // glossy sheen, drawn over the art
        card.addView(cover, new LinearLayout.LayoutParams(px(COVER), px(COVER)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(px(14));
        clp.setMarginEnd(px(8));
        card.addView(col, clp);

        source = text(9.5f, 500, DIM);
        source.setLetterSpacing(0.08f);
        // Hanya tajuk lagu dan nama penyanyi: tiada label app, garisan kemajuan atau masa.
        title = text(16f, 500, TEXT);
        col.addView(title, top(0));
        artist = text(13f, 400, 0xB3FFFFFF);
        col.addView(artist, top(3));

        LinearLayout progress = new LinearLayout(ctx);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        bar = new Bar(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, px(3), 1f);
        blp.setMarginEnd(px(8));
        progress.addView(bar, blp);
        time = text(9.5f, 500, DIM);
        progress.addView(time); // tak dipaparkan (progress tak dimasukkan ke dalam kad)

        int[] icons = {R.drawable.ic_previous, R.drawable.ic_pause, R.drawable.ic_next};
        for (int i = 0; i < BUTTONS; i++) {
            ImageView b = new ImageView(ctx);
            b.setImageResource(icons[i]);
            b.setBackgroundResource(R.drawable.np_button);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setImageTintList(ColorStateList.valueOf(TEXT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(36), px(36));
            if (i > 0) lp.setMarginStart(px(8));
            card.addView(b, lp);
            buttons[i] = b;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        if (started) return;
        started = true;
        generation++;
        enabled = true;
        card.setVisibility(View.VISIBLE); // sentiasa ada; "Nothing playing" bila tiada lagu
        if (info == null) showIdle();
        adb.ensure();
        main.post(dumpsysPoll); // the helper is only started once something is playing
    }

    void stop() {
        if (!started) return;
        started = false;
        generation++;
        main.removeCallbacks(tick);
        main.removeCallbacks(dumpsysPoll);
        AdbKeys.Hid h = helper;
        helper = null;
        if (h != null) new Thread(() -> adb.stop(h), "media-helper-stop").start();
    }

    /** Settings → Now Playing card off: stop watching and take the card down. */
    void disable() {
        stop();
        enabled = false;
        card.setVisibility(View.GONE);
        if (shown) {
            shown = false;
            listener.onShownChanged(false);
        }
    }

    /** Hidden while the app grid has the screen: pause the tick and dumpsys polling then. */
    void setVisible(boolean v) {
        if (visibleOnScreen == v) return;
        visibleOnScreen = v;
        main.removeCallbacks(tick);
        main.removeCallbacks(dumpsysPoll);
        if (needsTick()) main.post(tick);
        if (v && started && helper == null) main.post(dumpsysPoll);
    }

    boolean isShown() {
        return shown;
    }

        /** Untuk screensaver. */
    String screensaverTitle() {
        return shown ? title.getText().toString() : null;
    }

    String screensaverArtist() {
        return shown && artist.getVisibility() == View.VISIBLE ? artist.getText().toString() : "";
    }

    boolean screensaverLive() {
        Info i = info;
        return shown && i != null && i.duration <= 0 && helper != null;
    }

    Drawable screensaverCover() {
        Drawable d = cover.getDrawable();
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }
    View button(int i) {
        return buttons[i];
    }

    // ---------------------------------------------------------------- actions

    void press(int i) {
        Info in = info;
        if (in == null) return;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        switch (i) {
            case PLAY_PAUSE:
                media(am, in.state == PlaybackState.STATE_PLAYING ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case PREV:
                media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case NEXT:
                media(am, KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            default:
                return;
        }
        // Show the new state soon (the helper reports it within a second by itself).
        if (helper == null) {
            main.removeCallbacks(dumpsysPoll);
            main.postDelayed(dumpsysPoll, 600);
        }
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    // ---------------------------------------------------------------- source 1: helper over ADB

    private boolean helperStarting;

    /** Only while a media session exists; the helper costs ~23 MB while it runs. */
    private void startHelper() {
        if (!started || helper != null || helperStarting) return;
        if (adb.state() != AdbKeys.State.READY) {
            adb.ensure(); // no ADB: dumpsys keeps the card up to date without art
            return;
        }
        helperStarting = true;
        int gen = generation;
        new Thread(() -> {
            AdbKeys.Hid h = adb.state() == AdbKeys.State.READY ? adb.shell(
                    "app_process -cp " + ctx.getPackageCodePath() + " /system/bin " + MediaHelper.class.getName()) : null;
            main.post(() -> {
                helperStarting = false;
                if (h == null) return; // dumpsys polling carries on and will try again
                if (gen != generation || !started) {
                    new Thread(() -> adb.stop(h)).start();
                    return;
                }
                helper = h;
                main.removeCallbacks(dumpsysPoll);
                Thread reader = new Thread(() -> readHelper(h, gen), "media-helper-read");
                reader.setDaemon(true);
                reader.start();
            });
        }, "media-helper-start").start();
    }

    private void readHelper(AdbKeys.Hid h, int gen) {
        try {
            for (String line; (line = h.stream.getSource().readUtf8Line()) != null; ) {
                if (!line.startsWith("{")) continue;
                JSONObject o = new JSONObject(line);
                if (o.has("error")) continue;
                Info i = null;
                Bitmap art = null;
                if (o.has("pkg")) {
                    i = new Info();
                    i.pkg = o.getString("pkg");
                    i.title = o.optString("title", null);
                    i.artist = o.optString("artist", null);
                    i.state = o.optInt("state");
                    i.pos = o.optLong("pos");
                    i.updated = o.optLong("updated");
                    i.speed = (float) o.optDouble("speed", 1);
                    i.duration = o.optLong("duration");
                    i.actions = o.optLong("actions");
                    i.artId = o.optInt("artId");
                    i.artUri = o.optString("artUri", null);
                    if (o.has("art")) {
                        byte[] jpg = Base64.decode(o.getString("art"), Base64.DEFAULT);
                        art = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    }
                }
                Info fi = i;
                Bitmap fart = art;
                main.post(() -> {
                    if (gen != generation) return;
                    if (fart != null && fi != null) {
                        helperArt = fart;
                        helperArtId = fi.artId;
                    }
                    show(fi);
                    if (fi == null) stopHelper(); // playback ended: free the helper
                });
            }
        } catch (Throwable ignored) {
        }
        // Helper ended (stopped, ADB off, connection dropped): back to dumpsys polling.
        main.post(() -> {
            if (helper == h) helper = null;
            if (gen == generation && started) {
                main.removeCallbacks(dumpsysPoll);
                main.post(dumpsysPoll);
            }
        });
    }

    private void stopHelper() {
        AdbKeys.Hid h = helper;
        helper = null;
        if (h != null) new Thread(() -> adb.stop(h), "media-helper-stop").start();
        main.removeCallbacks(dumpsysPoll);
        if (started) main.post(dumpsysPoll);
    }

    // ---------------------------------------------------------------- source 2: dumpsys

    private static final Pattern STATE = Pattern.compile(
            "state=PlaybackState \\{state=(?:[A-Za-z_]+\\()?(\\d+)\\)?, position=(-?\\d+),.*?speed=([\\d.]+), updated=(\\d+), actions=(\\d+)");

    /**
     * Best session from `dumpsys media_session` (needs DUMP), or null. Blocking: run off the main
     * thread. Shared with the phone remote, which needs it while another app is on screen too.
     */
    static Info readSession(Context ctx) {
        Info best = null;
        int bestRank = 0;
        try {
            Process p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                Info cur = null;
                boolean inStack = false;
                for (String line; (line = r.readLine()) != null; ) {
                    String t = line.trim();
                    if (t.startsWith("Sessions Stack")) {
                        inStack = true;
                        continue;
                    }
                    if (!inStack) continue;
                    if (t.startsWith("package=")) {
                        cur = new Info();
                        cur.pkg = t.substring(8);
                    } else if (cur != null && t.startsWith("state=PlaybackState")) {
                        Matcher m = STATE.matcher(t);
                        if (m.find()) {
                            cur.state = Integer.parseInt(m.group(1));
                            cur.pos = Long.parseLong(m.group(2));
                            cur.speed = Float.parseFloat(m.group(3));
                            cur.updated = Long.parseLong(m.group(4));
                            cur.actions = Long.parseLong(m.group(5));
                        }
                    } else if (cur != null && t.startsWith("metadata:")) {
                        int d = t.indexOf("description=");
                        if (d >= 0 && !cur.pkg.equals(ctx.getPackageName())) {
                            // MediaDescription.toString(): "title, subtitle, description"
                            String[] parts = t.substring(d + 12).split(", ", 3);
                            cur.title = blank(parts[0]);
                            cur.artist = parts.length > 1 ? blank(parts[1]) : null;
                            int rank = rank(cur.state);
                            if (rank > bestRank) {
                                best = cur;
                                bestRank = rank;
                            }
                        }
                        cur = null;
                    } else if (t.startsWith("Media button session")) {
                        break;
                    }
                }
            }
            p.destroy();
        } catch (Exception ignored) {
        }
        return best;
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() || s.equals("null") ? null : s;
    }

    /** Nama keadaan main untuk dashboard remote telefon (RemoteDashboard menghantarnya sebagai "state"). */
    static String stateLabel(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return "playing";
            case PlaybackState.STATE_PAUSED:
                return "paused";
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
                return "buffering";
            case PlaybackState.STATE_STOPPED:
                return "stopped";
            default:
                return "idle";
        }
    }

    private static int rank(int state) {
        return state == PlaybackState.STATE_PLAYING ? 3
                : state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING ? 2
                : state == PlaybackState.STATE_PAUSED ? 1 : 0;
    }

    // ---------------------------------------------------------------- display

    /** Ikon main bila dijeda. Tukar kepada R.drawable.ic_play kalau projek ada ikon sendiri. */
    private static final int PLAY_ICON = android.R.drawable.ic_media_play;
    private static final long ACTION_PREV = PlaybackState.ACTION_SKIP_TO_PREVIOUS;
    private static final long ACTION_NEXT = PlaybackState.ACTION_SKIP_TO_NEXT;

    private int shownIcon = -1;
    private String labelPkg, labelText;

    private void show(Info i) {
        info = i;
        last = i;
        main.removeCallbacks(tick);
        if (i == null) {
            coverKey = null;
            helperArt = null;
            if (enabled) showIdle();
            if (shown) {
                shown = false;
                listener.onShownChanged(false);
            }
            return;
        }
        String label = appLabel(i.pkg);
        boolean playing = i.state == PlaybackState.STATE_PLAYING;
        boolean live = i.duration <= 0 && helper != null;

        title.setText(!TextUtils.isEmpty(i.title) ? i.title : label);
        if (TextUtils.isEmpty(i.artist)) {
            artist.setVisibility(View.GONE);
        } else {
            artist.setText(i.artist);
            artist.setVisibility(View.VISIBLE);
        }
        setSource(label, live);
        setCover(i);

        int icon = playing ? R.drawable.ic_pause : PLAY_ICON;
        if (icon != shownIcon) {
            shownIcon = icon;
            buttons[PLAY_PAUSE].setImageResource(icon);
        }
        // Bila app laporkan senarai tindakan, malapkan Sebelumnya/Seterusnya yang tak disokong.
        buttons[PLAY_PAUSE].setAlpha(1f);
        buttons[PREV].setAlpha(supported(i.actions, ACTION_PREV) ? 1f : 0.35f);
        buttons[NEXT].setAlpha(supported(i.actions, ACTION_NEXT) ? 1f : 0.35f);

        if (!shown) {
            shown = true;
            card.setVisibility(View.VISIBLE);
            listener.onShownChanged(true);
        }
        cover.setImageTintList(null);
        updateProgress();
        if (needsTick()) main.postDelayed(tick, 1000);
    }

    /** Tiada lagu: kad kekal di skrin, butang dimalapkan. */
    private void showIdle() {
        source.setText("NOW PLAYING");
        title.setText("Nothing playing");
        artist.setVisibility(View.GONE);
        int p = px(26);
        cover.setPadding(p, p, p, p);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setImageResource(PLAY_ICON);
        cover.setImageTintList(ColorStateList.valueOf(0x59FFFFFF));
        bar.set(0f, TEXT);
        time.setText("");
        shownIcon = PLAY_ICON;
        buttons[PLAY_PAUSE].setImageResource(PLAY_ICON);
        for (ImageView b : buttons) b.setAlpha(0.35f);
    }

    private static boolean supported(long actions, long flag) {
        return actions == 0 || (actions & flag) != 0;
    }

    private void setSource(String label, boolean live) {
        String s = label.toUpperCase(Locale.getDefault());
        if (live) {
            String full = s + "  \u25CF LIVE";
            SpannableString sp = new SpannableString(full);
            sp.setSpan(new ForegroundColorSpan(LIVE), s.length() + 2, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            source.setText(sp);
        } else {
            source.setText(s);
        }
    }

    /** Cover dari helper kalau ada; kalau tidak, ikon app. Hanya dilukis semula bila berubah. */
    private void setCover(Info i) {
        Bitmap art = helper != null && helperArt != null && helperArtId == i.artId ? helperArt : null;
        String key = art != null ? "art:" + i.artId + ":" + i.pkg : "app:" + i.pkg;
        if (key.equals(coverKey)) return;
        coverKey = key;
        if (art != null) {
            cover.setPadding(0, 0, 0, 0);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageBitmap(art);
        } else {
            Drawable d = null;
            try {
                d = ctx.getPackageManager().getApplicationIcon(i.pkg);
            } catch (Exception ignored) {
            }
            int p = px(16);
            cover.setPadding(p, p, p, p);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setImageDrawable(d);
        }
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "";
        if (pkg.equals(labelPkg) && labelText != null) return labelText;
        String l = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) l = cs.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        labelPkg = pkg;
        labelText = l;
        return l;
    }

    /** Lirik sudah dibuang dari kad ini. Kaedah ini kekal kosong supaya kod lama yang memanggilnya masih dikompil. */
    void setLyric(String line) {
    }

    // ---------------------------------------------------------------- progress

    private boolean needsTick() {
        Info i = info;
        return false; // tiada garisan kemajuan lagi, tak perlu tick setiap saat
    }

    private void updateProgress() {
        Info i = info;
        if (i == null) return;
        boolean playing = i.state == PlaybackState.STATE_PLAYING;
        long pos = Math.max(0, i.pos);
        if (playing && i.updated > 0) {
            long extra = (long) ((SystemClock.elapsedRealtime() - i.updated) * i.speed);
            if (extra > 0) pos += extra;
        }
        if (i.duration > 0) {
            pos = Math.min(pos, i.duration);
            bar.set(pos / (float) i.duration, TEXT);
            time.setText(format(pos));
            time.setTextColor(DIM);
        } else if (helper != null) {
            bar.set(1f, LIVE);
            time.setText("LIVE");
            time.setTextColor(LIVE);
        } else {
            bar.set(0f, TEXT);
            time.setText(format(pos));
            time.setTextColor(DIM);
        }
    }

    private static String format(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- view helpers

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTypeface(Fonts.outfit(ctx, weight));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private LinearLayout.LayoutParams top(float dps) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(dps);
        return lp;
    }

    /** Bar kemajuan nipis: trek malap, isian di atasnya. */
    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;
        private int color = TEXT;

        Bar(Context c) {
            super(c);
        }

        void set(float f, int c) {
            f = Math.max(0f, Math.min(1f, f));
            if (f == fraction && c == color) return;
            fraction = f;
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2f, w = getWidth();
            rect.set(0, 0, w, h);
            paint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(rect, r, r, paint);
            if (fraction > 0f) {
                rect.right = w * fraction;
                paint.setColor(color);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }
}
