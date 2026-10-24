package my.rafhan.app.tvhome;

/**
 * Characters to USB-HID keyboard reports for the phone remote's virtual keyboard. The keypad
 * (arrows, Enter) is handled separately in {@link AdbKeys}; this is the part that types text.
 * Pure, so it can be unit tested without a TV.
 */
final class HidKeys {
    private HidKeys() {}

    /** The shift modifier bit in a keyboard report. */
    static final int SHIFT = 0x02;

    /** {modifiers, HID usage} for a US keyboard, or null for a character it can't type. */
    static int[] usageFor(char c) {
        if (c >= 'a' && c <= 'z') return new int[] {0, 0x04 + (c - 'a')};
        if (c >= 'A' && c <= 'Z') return new int[] {SHIFT, 0x04 + (c - 'A')};
        if (c >= '1' && c <= '9') return new int[] {0, 0x1E + (c - '1')};
        String plain = "0 -=[]\\;'`,./";
        int[] plainUsage = {0x27, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
        int i = plain.indexOf(c);
        if (i >= 0) return new int[] {0, plainUsage[i]};
        String shifted = ")!@#$%^&*(_+{}|:\"~<>?";
        int[] shiftedUsage = {0x27, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26,
                0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
        i = shifted.indexOf(c);
        if (i >= 0) return new int[] {SHIFT, shiftedUsage[i]};
        if (c == '\n') return new int[] {0, 0x28};
        return null;
    }
}
