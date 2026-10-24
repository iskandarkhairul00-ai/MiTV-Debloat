package my.rafhan.app.tvhome;

import android.os.SystemClock;

import java.util.Locale;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * The PIN half of pairing: a six-digit PIN with a few tries, and a lockout that grows each time
 * pairing fails, so a phone (or anything else on the Wi-Fi) can't grind it. Kept free of the HTTP
 * server and given its clocks and random, so it can be unit tested without a TV.
 */
final class RemotePairing {
    static final int PIN_DIGITS = 6;
    static final long PIN_MS = 60_000;
    static final int PIN_TRIES = 5;
    /** Wrong PINs (across PINs) before pairing locks, and how the wait grows. */
    static final int FAILS_BEFORE_LOCK = 5;
    static final long LOCK_BASE_MS = 30_000;
    static final long LOCK_MAX_MS = 15 * 60_000;
    /** A new PIN can't be requested more often than this. */
    static final long REQUEST_GAP_MS = 3_000;

    enum Result { OK, WRONG, EXPIRED, TRIED_OUT, LOCKED }

    private final Random random;
    private final LongSupplier realtime; // monotonic: PIN lifetime, request spacing
    private final LongSupplier wall;     // wall clock: lockout (survives a reboot in spirit)

    private String pin;
    private long pinUntil;
    private int tries;
    private long lastRequest;
    private int failures;
    private long blockedUntil;

    RemotePairing(Random random) {
        this(random, SystemClock::elapsedRealtime, System::currentTimeMillis);
    }

    RemotePairing(Random random, LongSupplier realtime, LongSupplier wall) {
        this.random = random;
        this.realtime = realtime;
        this.wall = wall;
    }

    /** Millis pairing is still locked for, or 0. */
    synchronized long blockedForMs() {
        long left = blockedUntil - wall.getAsLong();
        return left > 0 ? left : 0;
    }

    /** True while a PIN was requested less than {@link #REQUEST_GAP_MS} ago. */
    synchronized boolean tooSoon() {
        return realtime.getAsLong() - lastRequest < REQUEST_GAP_MS;
    }

    /** Generates and remembers a fresh PIN. Call only once {@link #blockedForMs()} is 0. */
    synchronized String newPin() {
        lastRequest = realtime.getAsLong();
        pin = String.format(Locale.ROOT, "%0" + PIN_DIGITS + "d", random.nextInt(pow10(PIN_DIGITS)));
        pinUntil = realtime.getAsLong() + PIN_MS;
        tries = 0;
        return pin;
    }

    /** Drops the current PIN (the TV's card was hidden, or pairing locked). */
    synchronized void clear() {
        pin = null;
    }

    synchronized int triesLeft() {
        return PIN_TRIES - tries;
    }

    /** Checks a PIN the phone sent back. A success also forgets the failure count. */
    synchronized Result confirm(String given) {
        if (blockedForMs() > 0) {
            pin = null;
            return Result.LOCKED;
        }
        if (pin == null || realtime.getAsLong() > pinUntil) {
            pin = null;
            return Result.EXPIRED;
        }
        if (pin.equals(given)) {
            pin = null;
            failures = 0;
            blockedUntil = 0;
            return Result.OK;
        }
        tries++;
        failures++;
        if (failures >= FAILS_BEFORE_LOCK) {
            pin = null;
            blockedUntil = wall.getAsLong() + lockMs(failures);
            return Result.LOCKED;
        }
        if (tries >= PIN_TRIES) {
            pin = null;
            return Result.TRIED_OUT;
        }
        return Result.WRONG;
    }

    /** 30s, then doubling each further failure, capped at 15 minutes. */
    static long lockMs(int failures) {
        long ms = LOCK_BASE_MS << Math.min(10, Math.max(0, failures - FAILS_BEFORE_LOCK));
        return Math.min(ms, LOCK_MAX_MS);
    }

    static int pow10(int n) {
        int v = 1;
        for (int i = 0; i < n; i++) v *= 10;
        return v;
    }
}
