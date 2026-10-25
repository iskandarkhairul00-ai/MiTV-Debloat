package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;

/** What the info overlay over apps shows. */
public class StatusOverlayTest {
    @Test
    public void speedIsDecimalBitsPerSecond() {
        assertEquals("0 Kb", StatusOverlay.formatSpeed(0));
        assertEquals("0 Kb", StatusOverlay.formatSpeed(999));
        assertEquals("850 Kb", StatusOverlay.formatSpeed(850_000));
        assertEquals("999 Kb", StatusOverlay.formatSpeed(999_999));
        assertEquals("1.0 Mb", StatusOverlay.formatSpeed(1_000_000));
        assertEquals("12.4 Mb", StatusOverlay.formatSpeed(12_400_000));
        assertEquals("1.2 Gb", StatusOverlay.formatSpeed(1_200_000_000));
    }

    @Test
    public void uptimeAddsDaysPastADay() {
        assertEquals("0h0m", StatusOverlay.formatUptime(59_000));
        assertEquals("23h6m", StatusOverlay.formatUptime((23 * 60 + 6) * 60_000L));
        assertEquals("1d0h0m", StatusOverlay.formatUptime(24 * 60 * 60_000L));
        assertEquals("2d3h15m", StatusOverlay.formatUptime(((2 * 24 + 3) * 60 + 15) * 60_000L));
    }

    @Test
    public void elapsedAddsHoursPastAnHour() {
        assertEquals("00:00", StatusOverlay.formatElapsed(0));
        assertEquals("04:07", StatusOverlay.formatElapsed((4 * 60 + 7) * 1000L));
        assertEquals("59:59", StatusOverlay.formatElapsed(3599_000L));
        assertEquals("1:04:07", StatusOverlay.formatElapsed((3600 + 4 * 60 + 7) * 1000L));
    }

    @Test
    public void clockIs24HourWithSeconds() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 25, 7, 5, 9);
        assertEquals("07:05:09", StatusOverlay.formatClock(c));
        c.set(Calendar.HOUR_OF_DAY, 23);
        assertEquals("23:05:09", StatusOverlay.formatClock(c));
    }
}
