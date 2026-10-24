package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Random;

/** The PIN and lockout rules, with a fake clock. */
public class RemotePairingTest {
    private long realtime;
    private long wall;
    private RemotePairing pairing;

    @Before
    public void setUp() {
        realtime = 1_000_000;
        wall = 1_700_000_000_000L;
        pairing = new RemotePairing(new Random(7), () -> realtime, () -> wall);
    }

    private void advance(long ms) {
        realtime += ms;
        wall += ms;
    }

    private static String wrongFor(String pin) {
        return "000000".equals(pin) ? "111111" : "000000";
    }

    @Test
    public void aNewPinIsSixDigits() {
        String pin = pairing.newPin();
        assertEquals(RemotePairing.PIN_DIGITS, pin.length());
        assertTrue(pin, pin.matches("\\d{6}"));
    }

    @Test
    public void aSecondPinCannotBeRequestedImmediately() {
        pairing.newPin();
        assertTrue(pairing.tooSoon());
        advance(RemotePairing.REQUEST_GAP_MS - 1);
        assertTrue(pairing.tooSoon());
        advance(1);
        assertFalse(pairing.tooSoon());
    }

    @Test
    public void theRightPinSucceeds() {
        String pin = pairing.newPin();
        assertEquals(RemotePairing.Result.OK, pairing.confirm(pin));
    }

    @Test
    public void wrongPinsCountDownTheTries() {
        String pin = pairing.newPin();
        String wrong = wrongFor(pin);
        assertEquals(RemotePairing.PIN_TRIES, pairing.triesLeft());
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrong));
        assertEquals(4, pairing.triesLeft());
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrong));
        assertEquals(3, pairing.triesLeft());
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrong));
        assertEquals(2, pairing.triesLeft());
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrong));
        assertEquals(1, pairing.triesLeft());
        // The fifth wrong PIN locks pairing rather than just ending this PIN.
        assertEquals(RemotePairing.Result.LOCKED, pairing.confirm(wrong));
    }

    @Test
    public void aCorrectPinStillWorksAfterAFewMistakes() {
        String pin = pairing.newPin();
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrongFor(pin)));
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrongFor(pin)));
        assertEquals(RemotePairing.Result.OK, pairing.confirm(pin));
    }

    @Test
    public void lockoutBlocksNewPinsUntilTheWaitPasses() {
        String pin = pairing.newPin();
        for (int i = 0; i < RemotePairing.FAILS_BEFORE_LOCK - 1; i++) {
            assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrongFor(pin)));
        }
        assertEquals(RemotePairing.Result.LOCKED, pairing.confirm(wrongFor(pin)));
        assertEquals(RemotePairing.LOCK_BASE_MS, pairing.blockedForMs());

        // Still locked a moment later, even for the PIN that was showing.
        advance(RemotePairing.LOCK_BASE_MS - 1);
        assertTrue(pairing.blockedForMs() > 0);
        assertEquals(RemotePairing.Result.LOCKED, pairing.confirm(pin));

        advance(1);
        assertEquals(0, pairing.blockedForMs());
        assertFalse(pairing.newPin().isEmpty());
    }

    @Test
    public void aSuccessClearsTheFailureCount() {
        String pin = pairing.newPin();
        for (int i = 0; i < RemotePairing.FAILS_BEFORE_LOCK - 1; i++) {
            assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrongFor(pin)));
        }
        assertEquals(RemotePairing.Result.OK, pairing.confirm(pin));
        assertEquals(0, pairing.blockedForMs());

        // One more mistake must not lock pairing: the count started again at zero.
        advance(RemotePairing.REQUEST_GAP_MS);
        String next = pairing.newPin();
        assertEquals(RemotePairing.Result.WRONG, pairing.confirm(wrongFor(next)));
        assertEquals(0, pairing.blockedForMs());
    }

    @Test
    public void aPinExpiresAfterAMinute() {
        String pin = pairing.newPin();
        advance(RemotePairing.PIN_MS + 1);
        assertEquals(RemotePairing.Result.EXPIRED, pairing.confirm(pin));
    }

    @Test
    public void clearingDropsThePin() {
        String pin = pairing.newPin();
        pairing.clear();
        assertEquals(RemotePairing.Result.EXPIRED, pairing.confirm(pin));
    }

    @Test
    public void theLockoutWaitDoublesUpTo15Minutes() {
        assertEquals(30_000, RemotePairing.lockMs(RemotePairing.FAILS_BEFORE_LOCK));
        assertEquals(60_000, RemotePairing.lockMs(RemotePairing.FAILS_BEFORE_LOCK + 1));
        assertEquals(120_000, RemotePairing.lockMs(RemotePairing.FAILS_BEFORE_LOCK + 2));
        assertEquals(RemotePairing.LOCK_MAX_MS, RemotePairing.lockMs(RemotePairing.FAILS_BEFORE_LOCK + 10));
        assertEquals(RemotePairing.LOCK_MAX_MS, RemotePairing.lockMs(100));
    }
}
