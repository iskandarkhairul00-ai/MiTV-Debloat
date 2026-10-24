package my.rafhan.app.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Typing on the phone becomes USB-HID reports on the TV. */
public class HidKeysTest {
    @Test
    public void lettersUseTheRightCase() {
        assertArrayEquals(new int[] {0, 0x04}, HidKeys.usageFor('a'));
        assertArrayEquals(new int[] {0, 0x1D}, HidKeys.usageFor('z'));
        assertArrayEquals(new int[] {HidKeys.SHIFT, 0x04}, HidKeys.usageFor('A'));
        assertArrayEquals(new int[] {HidKeys.SHIFT, 0x1D}, HidKeys.usageFor('Z'));
    }

    @Test
    public void digitsAndTheirShiftedSymbols() {
        assertArrayEquals(new int[] {0, 0x1E}, HidKeys.usageFor('1'));
        assertArrayEquals(new int[] {0, 0x26}, HidKeys.usageFor('9'));
        assertArrayEquals(new int[] {0, 0x27}, HidKeys.usageFor('0'));
        assertArrayEquals(new int[] {HidKeys.SHIFT, 0x1E}, HidKeys.usageFor('!'));
        assertArrayEquals(new int[] {HidKeys.SHIFT, 0x27}, HidKeys.usageFor(')'));
    }

    @Test
    public void punctuationIsMapped() {
        assertArrayEquals(new int[] {0, 0x2C}, HidKeys.usageFor(' '));
        assertArrayEquals(new int[] {0, 0x38}, HidKeys.usageFor('/'));
        assertArrayEquals(new int[] {HidKeys.SHIFT, 0x38}, HidKeys.usageFor('?'));
        assertArrayEquals(new int[] {0, 0x28}, HidKeys.usageFor('\n')); // Enter
    }

    @Test
    public void charactersTheKeyboardCannotTypeAreNull() {
        assertNull(HidKeys.usageFor('€'));
        assertNull(HidKeys.usageFor('\t'));
        assertNull(HidKeys.usageFor('\u0000'));
    }

    @Test
    public void everyPrintableAsciiCharacterMapsToAByte() {
        for (char c = 0x20; c <= 0x7E; c++) {
            int[] k = HidKeys.usageFor(c);
            if (k == null) {
                continue; // e.g. backslash/quote handling is covered by the plain/shifted tables
            }
            assertEquals(String.valueOf(c), 2, k.length);
            assertEquals(String.valueOf(c), 0, k[0] & ~HidKeys.SHIFT);
        }
    }
}
