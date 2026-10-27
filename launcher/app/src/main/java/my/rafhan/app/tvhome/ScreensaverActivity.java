package my.rafhan.app.tvhome;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: jam besar di tengah, kad Now Playing di kiri bawah.
 * Bila lagu ada lirik berpenanda masa (LRCLIB): jam mengecil ke kiri atas dan lirik besar
 * mengisi ruang kosong di tengah, di atas latar kabur dari cover art.
 * Anti burn-in: elemen beralih sedikit setiap 2 minit, malap lagi selepas 10 minit.
 * Keluar: tekan mana-mana butang atau sentuh.
 */
public class ScreensaverActivity extends Activity {

    public static final String EXTRA_TITLE = "ss_title";
    public static final String EXTRA_ARTIST = "ss_artist";
    public static final String EXTRA_LIVE = "ss_live";
    public static final String EXTRA_HIJRI = "ss_hijri";

    /** Pilihan: MainActivity boleh set cover art sebelum startActivity. */
    public static Drawable cover;

    private static final int[][] SHIFTS = {{0, 0}, {3, 2}, {-3, -2}, {2, -3}};

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView clock;
    private View centerBox;
    private View card;
    private View dimOverlay;

    // ---- Lirik ----
    private static final long LYRIC_TICK_MS = 250;
    private static final long POLL_MS = 4000;
    private TextView dateView, hijriView, cardTitle, cardArtist;
    private ImageView bg, artView;
    private LinearLayout lyricBox;
    private TextView lyPrev, lyCur, lyNext;
    private FrameLayout.LayoutParams boxLp;
    private boolean lyricsMode;
    private NowPlayingPanel.Info info;
    private LyricsProvider.Lyrics lyrics;
    private String lyricsKey, cardTrack;
    private int lastIdx = -2;
    private AudioManager audio;
    private final ExecutorService pollWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService fetchWorker = Executors.newSingleThreadExecutor();
    private int shiftIdx = 0;
    private long created;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("ms", "MY"));

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(timeFmt.format(new Date()));
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable shiftTask = new Runnable() {
        @Override public void run() {
            shiftIdx = (shiftIdx + 1) % SHIFTS.length;
            int dx = dp(SHIFTS[shiftIdx][0]);
            int dy = dp(SHIFTS[shiftIdx][1]);
            centerBox.animate().translationX(dx).translationY(dy).setDuration(800).start();
            if (card.getVisibility() == View.VISIBLE) {
                card.animate().translationX(dx).translationY(dy).setDuration(800).start();
            }
            h.postDelayed(this, 120_000);
        }
    };

    private final Runnable dimTask = new Runnable() {
        @Override public void run() {
            dimOverlay.animate().alpha(0.45f).setDuration(2000).start();
        }
    };

    private final Runnable lyricTask = new Runnable() {
        @Override public void run() {
            tickLyrics();
            h.postDelayed(this, LYRIC_TICK_MS);
        }
    };

    /** Baca sesi media (dumpsys) di thread latar: kesan tukar lagu / jeda semasa screensaver. */
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, POLL_MS);
            if (info == null && !audio.isMusicActive()) return;
            pollWorker.execute(() -> {
                NowPlayingPanel.Info i = NowPlayingPanel.readSession(ScreensaverActivity.this);
                h.post(() -> applyInfo(i));
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        created = SystemClock.uptimeMillis();
        audio = getSystemService(AudioManager.class);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0F14"));

        // ---- Latar: cover art dikaburkan (hanya bila lirik dipaparkan) ----
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0f);
        root.addView(bg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- Tengah: jam + tarikh (+ Hijri kalau dihantar) ----
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(120);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        box.addView(clock);

        TextView date = new TextView(this);
        dateView = date;
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(26);
        date.setText(dateFmt.format(new Date()));
        box.addView(date);

        String hijri = getIntent().getStringExtra(EXTRA_HIJRI);
        if (hijri != null && !hijri.isEmpty()) {
            TextView hj = new TextView(this);
            hijriView = hj;
            hj.setTextColor(0x80FFFFFF);
            hj.setTextSize(20);
            hj.setText(hijri);
            hj.setPadding(0, dp(4), 0, 0);
            box.addView(hj);
        }

        boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, boxLp);
        centerBox = box;

        // ---- Lirik besar: ruang kosong di tengah, antara jam (atas) dan kad (bawah) ----
        lyricBox = new LinearLayout(this);
        lyricBox.setOrientation(LinearLayout.VERTICAL);
        lyricBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        lyPrev = lyricLine(26, 0x59FFFFFF, false, 1);
        lyCur = lyricLine(52, Color.WHITE, true, 3);
        lyNext = lyricLine(30, 0x73FFFFFF, false, 1);
        lyCur.setPadding(0, dp(14), 0, dp(14));
        lyricBox.addView(lyPrev);
        lyricBox.addView(lyCur);
        lyricBox.addView(lyNext);
        FrameLayout.LayoutParams lyLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        lyLp.setMargins(dp(48), dp(150), dp(48), dp(150));
        root.addView(lyricBox, lyLp);
        lyricBox.setVisibility(View.GONE);

        // ---- Kiri bawah: kad Now Playing ----
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String artist = getIntent().getStringExtra(EXTRA_ARTIST);
        boolean live = getIntent().getBooleanExtra(EXTRA_LIVE, false);

        card = buildCard(title, artist, live);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(420), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cardLp.setMargins(dp(48), 0, 0, dp(48));
        root.addView(card, cardLp);
        card.setVisibility(title == null || title.isEmpty() ? View.GONE : View.VISIBLE);
        LyricsProvider.Query q0 = LyricsProvider.query(title, artist);
        cardTrack = q0 == null ? null : q0.track.toLowerCase(Locale.ROOT);
        setBlurFrom(cover);

        // ---- Lapisan malap (selepas 10 minit) ----
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        root.addView(dimOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private View buildCard(String title, String artist, boolean live) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(20));
        c.setBackground(bg);

        ImageView art = new ImageView(this);
        artView = art;
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(0xFF4B5D2A);
        artBg.setCornerRadius(dp(12));
        if (cover != null) {
            art.setImageDrawable(cover);
        } else {
            art.setBackground(artBg);
        }
        art.setClipToOutline(true);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        c.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView t = new TextView(this);
        cardTitle = t;
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setText(title == null ? "" : title);
        texts.addView(t);

        TextView a = new TextView(this);
        cardArtist = a;
        a.setTextColor(0xB3FFFFFF);
        a.setTextSize(15);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.setText(artist == null ? "" : artist);
        texts.addView(a);

        if (live) {
            TextView badge = new TextView(this);
            badge.setText("LIVE");
            badge.setTextColor(0xFFFF5A52);
            badge.setTextSize(12);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(0, dp(4), 0, 0);
            texts.addView(badge);
        }

        c.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return c;
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(tick);
        h.postDelayed(shiftTask, 120_000);
        h.postDelayed(dimTask, 600_000);
        h.post(lyricTask);
        h.post(pollTask); // terus baca sesi sekali, kemudian setiap 4 saat
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    /** Tekan Home: keluar dari screensaver juga. */
    @Override protected void onUserLeaveHint() {
        finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cover = null;
        pollWorker.shutdownNow();
        fetchWorker.shutdownNow();
    }

    // ================================================================ Lirik

    private TextView lyricLine(float sp, int color, boolean bold, int maxLines) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    /** Hasil bacaan sesi media (thread utama). */
    private void applyInfo(NowPlayingPanel.Info i) {
        if (isFinishing() || isDestroyed()) return;
        NowPlayingPanel.Info old = info;
        if (i != null && TextUtils.isEmpty(i.title)) i = null;
        if (i != null && i.duration <= 0 && old != null && old.duration > 0 && sameTrack(old, i)) {
            i.duration = old.duration; // dumpsys tak beri panjang lagu
        }
        info = i;
        if (i == null) {
            lyrics = null;
            lyricsKey = null;
            return;
        }

        LyricsProvider.Query q = LyricsProvider.query(i.title, i.artist);
        if (q == null) return;

        // Lagu bertukar semasa screensaver: kemas kini kad + cover.
        String track = q.track.toLowerCase(Locale.ROOT);
        if (!track.equals(cardTrack)) {
            boolean first = cardTrack == null && cover == null && artView.getDrawable() == null;
            cardTrack = track;
            cardTitle.setText(i.title);
            cardArtist.setText(i.artist == null ? "" : i.artist);
            card.setVisibility(View.VISIBLE);
            if (!first) {
                // Cover lagu baru tak diketahui: guna ikon aplikasi.
                try {
                    Drawable icon = getPackageManager().getApplicationIcon(i.pkg);
                    artView.setBackground(null);
                    artView.setImageDrawable(icon);
                    setBlurFrom(icon);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }

        if (!q.key.equals(lyricsKey)) {
            lyricsKey = q.key;
            lyrics = null;
            lastIdx = -2;
            LyricsProvider.Lyrics cached = LyricsProvider.cached(q.key);
            if (cached != null) {
                lyrics = cached;
            } else {
                LyricsProvider.fetch(fetchWorker, i.title, i.artist, i.duration, (k, l) -> {
                    if (isFinishing() || isDestroyed() || !k.equals(lyricsKey)) return;
                    lyrics = l;
                    lastIdx = -2;
                });
            }
        } else if (lyrics == null) {
            // Cache belum ada / rangkaian gagal tadi: minta lagi (LyricsProvider hadkan ulangan).
            LyricsProvider.fetch(fetchWorker, i.title, i.artist, i.duration, (k, l) -> {
                if (isFinishing() || isDestroyed() || !k.equals(lyricsKey)) return;
                lyrics = l;
                lastIdx = -2;
            });
        }
    }

    private static boolean sameTrack(NowPlayingPanel.Info a, NowPlayingPanel.Info b) {
        return a.title != null && a.title.equals(b.title);
    }

    /** Setiap 250 ms: tentukan baris semasa daripada kedudukan lagu. */
    private void tickLyrics() {
        NowPlayingPanel.Info i = info;
        if (i == null && lyrics == null && lyricsKey == null) {
            // Belum ada bacaan sendiri: guna snapshot dari kad Now Playing di home.
            NowPlayingPanel.Info snap = NowPlayingPanel.lastInfo;
            if (snap != null) applyInfo(snap);
            i = info;
        }
        LyricsProvider.Lyrics l = lyrics;
        boolean has = i != null && l != null && !l.isEmpty();
        setLyricsMode(has);
        if (!has) return;

        long pos = i.pos;
        if (i.state == PlaybackState.STATE_PLAYING && i.updated > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - i.updated) * i.speed);
        }
        int idx = l.indexAt(Math.max(0, pos));
        if (idx == lastIdx) return;
        lastIdx = idx;
        renderLines(l, idx);
    }

    private void renderLines(LyricsProvider.Lyrics l, int idx) {
        String cur = idx < 0 ? "" : l.texts[idx];
        String prev = "", next = "";
        for (int j = idx - 1; j >= 0; j--) {
            if (!l.texts[j].isEmpty()) { prev = l.texts[j]; break; }
        }
        for (int j = idx + 1; j < l.texts.length; j++) {
            if (!l.texts[j].isEmpty()) { next = l.texts[j]; break; }
        }
        if (cur.isEmpty()) cur = "\u266A"; // jeda instrumental
        lyCur.setTextSize(cur.length() > 70 ? 34 : cur.length() > 40 ? 42 : 52);
        swap(lyPrev, prev, true);
        swap(lyCur, cur, true);
        swap(lyNext, next, true);
    }

    /** Tukar teks dengan fade + naik sedikit. */
    private void swap(TextView v, String s, boolean animate) {
        if (s.contentEquals(v.getText())) return;
        v.setText(s);
        if (!animate) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0).setDuration(380).start();
    }

    /** Hidupkan/matikan susun atur lirik: jam ke kiri atas, lirik di tengah, latar kabur. */
    private void setLyricsMode(boolean on) {
        if (on == lyricsMode) return;
        lyricsMode = on;
        LinearLayout box = (LinearLayout) centerBox;
        if (on) {
            ((TextView) box.getChildAt(0)).setTextSize(64);
            dateView.setTextSize(22);
            if (hijriView != null) hijriView.setTextSize(17);
            box.setGravity(Gravity.START);
            boxLp.gravity = Gravity.TOP | Gravity.START;
            boxLp.setMargins(dp(48), dp(36), 0, 0);
            lyricBox.setAlpha(0f);
            lyricBox.setVisibility(View.VISIBLE);
            lyricBox.animate().alpha(1f).setDuration(600).start();
            if (bg.getDrawable() != null) bg.animate().alpha(0.55f).setDuration(1200).start();
        } else {
            ((TextView) box.getChildAt(0)).setTextSize(120);
            dateView.setTextSize(26);
            if (hijriView != null) hijriView.setTextSize(20);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            boxLp.gravity = Gravity.CENTER;
            boxLp.setMargins(0, 0, 0, 0);
            lyricBox.setVisibility(View.GONE);
            bg.setAlpha(0f);
            lyPrev.setText("");
            lyCur.setText("");
            lyNext.setText("");
        }
        box.setLayoutParams(boxLp);
    }

    /** Latar kabur yang murah: lukis cover kecil-kecil, ImageView yang besarkan semula. */
    private void setBlurFrom(Drawable d) {
        if (d == null) {
            bg.setImageDrawable(null);
            return;
        }
        try {
            Drawable.ConstantState cs = d.getConstantState();
            Drawable c = cs != null ? cs.newDrawable().mutate() : d;
            Bitmap big = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            c.setBounds(0, 0, 48, 48);
            c.draw(new Canvas(big));
            bg.setImageBitmap(Bitmap.createScaledBitmap(big, 12, 12, true));
            if (lyricsMode) bg.animate().alpha(0.55f).setDuration(1200).start();
        } catch (RuntimeException ignored) {
        }
    }

    // ---- Keluar: mana-mana butang / sentuhan ----
    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        // abaikan tekanan Back kedua yang masih berlangsung semasa skrin ini baru dibuka
        if (SystemClock.uptimeMillis() - created < 600) return true;
        finish();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
