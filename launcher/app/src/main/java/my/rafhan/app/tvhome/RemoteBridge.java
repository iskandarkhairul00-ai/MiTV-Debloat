package my.rafhan.app.tvhome;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands phone-remote D-pad keys to the launcher. MainActivity attaches while it's resumed, so
 * "attached" also means "the home screen is what's on the TV"; home listeners (the info overlay)
 * hear when that changes, on the main thread.
 */
final class RemoteBridge {
    interface Launcher {
        /** up, down, left, right, ok or back. Called on a server thread. */
        void key(String k);
    }

    private static volatile Launcher launcher;
    private static final CopyOnWriteArrayList<Runnable> homeListeners = new CopyOnWriteArrayList<>();

    private RemoteBridge() {}

    static void attach(Launcher l) {
        launcher = l;
        homeChanged();
    }

    static void detach(Launcher l) {
        if (launcher != l) return;
        launcher = null;
        homeChanged();
    }

    static void addHomeListener(Runnable r) {
        homeListeners.add(r);
    }

    static void removeHomeListener(Runnable r) {
        homeListeners.remove(r);
    }

    private static void homeChanged() {
        for (Runnable r : homeListeners) r.run();
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
