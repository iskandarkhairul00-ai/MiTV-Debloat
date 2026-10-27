package my.rafhan.app.tvhome;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.session.PlaybackState;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
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
 * Screensaver: jam besar di tengah, kad Now Playing di kiri bawah, lirik besar di kanan bawah.
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
    private int shiftIdx = 0;

    // ---- Lirik (lagu sahaja; radio live tiada tempoh jadi tiada lirik) ----
    private TextView lyric;
    private NowPlayingPanel.Info song;      // lagu yang sedang main bila screensaver bermula
    private long songPos, songUpdated;
    private float songSpeed = 1f;
    private boolean songPlaying;
    private boolean polling;
    private String shownLine = "";
    private final ExecutorService lyricWorker = Executors.newSingleThreadExecutor();
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
            lyric.animate().translationX(dx).translationY(dy).setDuration(800).start();
            h.postDelayed(this, 120_000);
        }
    };

    private final Runnable lyricTick = new Runnable() {
        @Override public void run() {
            updateLyric();
            h.postDelayed(this, 400);
        }
    };

    /** Selaras semula kedudukan lagu dengan `dumpsys media_session` setiap 5 saat. */
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            pollSession();
            h.postDelayed(this, 5000);
        }
    };

    private final Runnable dimTask = new Runnable() {
        @Override public void run() {
            dimOverlay.animate().alpha(0.45f).setDuration(2000).start();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        created = SystemClock.uptimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0F14"));

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
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(26);
        date.setText(dateFmt.format(new Date()));
        box.addView(date);

        String hijri = getIntent().getStringExtra(EXTRA_HIJRI);
        if (hijri != null && !hijri.isEmpty()) {
            TextView hj = new TextView(this);
            hj.setTextColor(0x80FFFFFF);
            hj.setTextSize(20);
            hj.setText(hijri);
            hj.setPadding(0, dp(4), 0, 0);
            box.addView(hj);
        }

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, boxLp);
        centerBox = box;

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

        // ---- Kanan bawah: lirik besar (ruang kosong di sebelah kad) ----
        NowPlayingPanel.Info s = NowPlayingPanel.last;
        if (s != null && s.duration > 0 && !TextUtils.isEmpty(s.title)
                && s.state == PlaybackState.STATE_PLAYING) {
            song = s;
            songPos = s.pos;
            songUpdated = s.updated > 0 ? s.updated : SystemClock.elapsedRealtime();
            songSpeed = s.speed;
            songPlaying = true;
        }
        lyric = new TextView(this);
        lyric.setTextColor(Color.WHITE);
        lyric.setTextSize(32);
        lyric.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lyric.setGravity(Gravity.BOTTOM | Gravity.END);
        lyric.setMaxLines(4);
        lyric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams lyricLp = new FrameLayout.LayoutParams(
                dp(420), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lyricLp.setMargins(0, 0, dp(48), dp(48));
        root.addView(lyric, lyricLp);

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
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setText(title == null ? "" : title);
        texts.addView(t);

        TextView a = new TextView(this);
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
        h.post(lyricTick);
        h.postDelayed(pollTask, 1500);
        h.postDelayed(shiftTask, 120_000);
        h.postDelayed(dimTask, 600_000);
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
        lyricWorker.shutdownNow();
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

    /** Baris lirik pada kedudukan semasa. Kosong kalau bukan lagu, berhenti, atau lagu sudah tamat. */
    private void updateLyric() {
        String line = "";
        if (song != null && songPlaying) {
            long pos = songPos + (long) ((SystemClock.elapsedRealtime() - songUpdated) * songSpeed);
            if (pos <= song.duration + 2000) {
                LyricsProvider.Query q = LyricsProvider.query(song.title, song.artist);
                if (q != null) {
                    LyricsProvider.Lyrics l = LyricsProvider.cached(q.key);
                    if (l == null) {
                        LyricsProvider.fetch(lyricWorker, song.title, song.artist, song.duration,
                                (key, res) -> updateLyric());
                    } else if (!l.isEmpty()) {
                        int idx = l.indexAt(pos);
                        if (idx >= 0) line = l.texts[idx];
                    }
                }
            }
        }
        if (!line.equals(shownLine)) {
            shownLine = line;
            lyric.setText(line);
        }
    }

    private void pollSession() {
        if (polling || song == null) return;
        polling = true;
        final android.content.Context ctx = getApplicationContext();
        new Thread(() -> {
            NowPlayingPanel.Info i = NowPlayingPanel.readSession(ctx);
            h.post(() -> {
                polling = false;
                applyPoll(i);
            });
        }, "ss-lyrics-poll").start();
    }

    private void applyPoll(NowPlayingPanel.Info i) {
        if (song == null) return;
        if (i == null) {            // main sudah tamat
            songPlaying = false;
            return;
        }
        // dumpsys boleh memotong tajuk pada koma pertama, jadi banding awalan sahaja.
        if (i.title == null || !song.title.startsWith(i.title)) {
            song = null;            // lagu bertukar: tempohnya tak diketahui, jadi tiada lirik
            return;
        }
        songPos = i.pos;
        songUpdated = i.updated > 0 ? i.updated : SystemClock.elapsedRealtime();
        songSpeed = i.speed;
        songPlaying = i.state == PlaybackState.STATE_PLAYING;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
