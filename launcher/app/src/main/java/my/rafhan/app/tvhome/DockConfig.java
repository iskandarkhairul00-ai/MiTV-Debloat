package my.rafhan.app.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dock packages, left to right: dock.txt in the app's external files dir, else res/values/dock.xml. */
final class DockConfig {
    private DockConfig() {}

    static List<String> load(Context ctx) {
        File custom = new File(ctx.getExternalFilesDir(null), "dock.txt");
        if (custom.isFile()) {
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(custom))) {
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
                }
            } catch (IOException ignored) {
            }
            if (!out.isEmpty()) return out;
        }
        return Arrays.asList(ctx.getResources().getStringArray(R.array.dock_packages));
    }
}
