package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dock packages, left to right: dock.txt in the app's external files dir, else res/values/dock.xml.
 * Edited from the TV (hold OK / Menu on a tile) and the phone remote; still hand-editable over ADB.
 */
final class DockConfig {
    static final int MAX = 5;

    interface Listener {
        void onDockChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private DockConfig() {}

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Changes whenever dock.txt is written (by us, the phone, or an ADB push); 0 if absent. */
    static long stamp(Context ctx) {
        File f = file(ctx);
        return f.isFile() ? f.lastModified() ^ f.length() : 0;
    }

    private static File file(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "dock.txt");
    }

    static synchronized List<String> load(Context ctx) {
        File custom = file(ctx);
        if (custom.isFile()) {
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(custom))) {
                for (String line; (line = r.readLine()) != null; ) lines.add(line);
            } catch (IOException ignored) {
            }
            return parse(lines); // may be empty: every dock app was removed
        }
        return new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_packages)));
    }

    /** dock.txt's lines: trimmed, comments and blanks dropped, duplicates removed, order kept. */
    static List<String> parse(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !out.contains(line)) out.add(line);
        }
        return out;
    }

    /** At most MAX packages, no nulls, empties or duplicates, order kept. */
    static List<String> clean(List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) if (p != null && !p.isEmpty() && !out.contains(p) && out.size() < MAX) out.add(p);
        return out;
    }

    /** Saves the order (at most MAX, duplicates dropped) and tells listeners on the main thread. */
    static synchronized boolean save(Context ctx, List<String> pkgs) {
        List<String> picked = clean(pkgs);
        File f = file(ctx), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            StringBuilder sb = new StringBuilder("# Dock apps, left to right (edited on the TV or phone)\n");
            for (String p : picked) sb.append(p).append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        if (!tmp.renameTo(f)) return false;
        main.post(() -> {
            for (Listener l : listeners) l.onDockChanged();
        });
        return true;
    }
}
