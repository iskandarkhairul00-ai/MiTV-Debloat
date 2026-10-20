package my.rafhan.app.tvhome;

/**
 * Hands phone-remote D-pad keys to the launcher. MainActivity attaches while it's resumed, so
 * "attached" also means "the home screen is what's on the TV".
 */
final class RemoteBridge {
    interface Launcher {
        /** up, down, left, right, ok or back. Called on a server thread. */
        void key(String k);
    }

    private static volatile Launcher launcher;

    private RemoteBridge() {}

    static void attach(Launcher l) {
        launcher = l;
    }

    static void detach(Launcher l) {
        if (launcher == l) launcher = null;
    }

    static boolean isHome() {
        return launcher != null;
    }

    static boolean key(String k) {
        Launcher l = launcher;
        if (l == null) return false;
        l.key(k);
        return true;
    }
}
