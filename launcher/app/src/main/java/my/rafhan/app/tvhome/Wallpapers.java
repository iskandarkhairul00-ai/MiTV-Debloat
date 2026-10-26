package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in wallpapers: seven photos from Wikimedia Commons' Featured pictures, downloaded once
 * (1920 px, 0.5–1 MB each) and cached in the app's files dir; nothing is bundled in the APK.
 * Eight more (the anime pack) come from this repo's docs folder the same way, and join the
 * Rotate and Weather modes once downloaded: open the Wallpaper list once to fetch them. Credits
 * are shown in Settings and the README (CC BY-SA needs them). Your own pictures go in
 * the Wallpapers folder on internal storage (the phone remote's Files tab has a shortcut to it);
 * each one is listed by file name. An older picture pushed over ADB as
 * files/wallpaper.{jpg,png,webp} is still offered as "Your picture".
 */
final class Wallpapers {
    static final String NONE = "none", CUSTOM = "custom", FILE = "file:", BING = "bing",
            ROTATE = "rotate", WEATHER = "weather", // every 5 min: all pictures / by weather

            DEFAULT = "perseids"; // Stargazer, the first in the list
    private static final String UA = "TvHome/1.0 (Android TV home launcher)";
    private static final String COMMONS = "https://thumb.wikimedia.org/wikipedia/commons/thumb/";
    /** The anime pack: JPGs (1920x1080) in the docs folder of this repo, downloaded once like the rest. */
    private static final String RAW = "https://raw.githubusercontent.com/iskandarkhairul00-ai/MiTV-Debloat/master/docs/";

    static final class Photo {
        final String id, name, credit, url;
        final boolean pack; // from this repo's docs folder, not Wikimedia

        Photo(String id, String name, String credit, String path) {
            this(id, name, credit, COMMONS + path, false);
        }

        private Photo(String id, String name, String credit, String url, boolean pack) {
            this.id = id;
            this.name = name;
            this.credit = credit;
            this.url = url;
            this.pack = pack;
        }

        /**
         * A picture of the anime pack. The id starts with its weather (clear, cloud, rain, storm):
         * the Weather mode picks by it, and the rain effect goes by the same name.
         */
        static Photo pack(String id, String name, String file) {
            return new Photo(id, name, "My pack", RAW + file, true);
        }
    }

    static final Photo[] BUILT_IN = {
            new Photo("perseids", "Stargazer", "Giles Laurent · CC BY-SA 4.0",
                    "2/23/018_Human_looking_at_the_stars_during_Perseids_with_the_Milky_Way_in_the_background_Photo_by_Giles_Laurent.jpg/"
                            + "1920px-018_Human_looking_at_the_stars_during_Perseids_with_the_Milky_Way_in_the_background_Photo_by_Giles_Laurent.jpg"),
            new Photo("seliger", "Lake Seliger", "Aleksandr Baydukov · CC BY-SA 4.0",
                    "d/d9/Lake_Seliger._Ostashkov._View_of_Voroniy_Island.jpg/"
                            + "1920px-Lake_Seliger._Ostashkov._View_of_Voroniy_Island.jpg"),
            new Photo("sunrise", "Misty sunrise", "Dietmar Rabich · CC BY-SA 4.0",
                    "7/76/D%C3%BClmen%2C_Umland_--_2014_--_7056.jpg/1920px-D%C3%BClmen%2C_Umland_--_2014_--_7056.jpg"),
            new Photo("m81", "Galaxy M81", "NASA, ESA, Hubble Heritage Team · public domain",
                    "6/63/Messier_81_HST.jpg/1920px-Messier_81_HST.jpg"),
            new Photo("domecreek", "Dome Creek", "Jakub Fryš · CC BY-SA 4.0",
                    "a/a3/Lake_in_Dome_Creek.jpg/1920px-Lake_in_Dome_Creek.jpg"),
            new Photo("forest", "Forest light", "Dietmar Rabich · CC BY-SA 4.0",
                    "3/3d/D%C3%BClmen%2C_Rorup%2C_NSG_Roruper_Holz_--_2021_--_8187-91.jpg/"
                            + "1920px-D%C3%BClmen%2C_Rorup%2C_NSG_Roruper_Holz_--_2021_--_8187-91.jpg"),
            new Photo("nightclouds", "Night clouds", "Martin Koitmäe · CC BY-SA 4.0",
                    "3/3f/Helkivad_%C3%B6%C3%B6pilved_Kuresoo_kohal.jpg/1920px-Helkivad_%C3%B6%C3%B6pilved_Kuresoo_kohal.jpg"),
            // Anime pack: the prefix of the id is the weather it is shown in.
            Photo.pack("clear_luffy", "Luffy", "luffy_putih.jpg"),
            Photo.pack("clear_colosseum", "Colosseum sunset", "colosseum_oren.jpg"),
            Photo.pack("cloud_sasuke", "Sasuke in purple field", "sasuke_ungu.jpg"),
            Photo.pack("cloud_king", "King", "king_merah.jpg"),
            Photo.pack("rain_belati", "Purple dagger", "belati_ungu.jpg"),
            Photo.pack("rain_sukuna", "Sukuna", "sukuna.jpg"),
            Photo.pack("storm_asta", "Asta", "asta.jpg"),
            Photo.pack("storm_hijau", "Green neon", "hijau_neon.jpg"),
    };

    /** How often the "Rotate" and "Weather" modes move to the next picture. */
    static final long ROTATE_MS = 5 * 60_000L;

    private Wallpapers() {}

    /**
     * "Rotate": the pictures in the Wallpapers folder, one after another, a new one every 5
     * minutes (on the clock: :00, :05, :10 ...). "Weather": the same, but only pictures whose
     * file name starts with the current weather (clear, cloud, rain, storm; e.g. rain1.jpg,
     * rain2.jpg); with none of those, any picture. With an empty folder, the default photo.
     */
    static File rotating(Context ctx, String choice) {
        List<File> pics = userPictures();
        pics.addAll(packPictures(ctx));
        pics.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        if (WEATHER.equals(choice)) {
            String cat = WeatherPanel.category(ctx);
            if (cat != null) {
                List<File> match = new ArrayList<>();
                for (File f : pics) if (f.getName().toLowerCase(Locale.ROOT).startsWith(cat)) match.add(f);
                if (!match.isEmpty()) pics = match;
            }
        }
        if (pics.isEmpty()) {
            File d = cached(ctx, DEFAULT);
            return d.isFile() ? d : null;
        }
        return pics.get((int) ((System.currentTimeMillis() / ROTATE_MS) % pics.size()));
    }

    static Photo photo(String id) {
        for (Photo p : BUILT_IN) if (p.id.equals(id)) return p;
        return null;
    }

    /** Where a built-in is cached (may not exist yet). */
    static File cached(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), "wallpapers"), id + ".jpg");
    }

    static boolean isCached(Context ctx, String id) {
        return cached(ctx, id).isFile();
    }

    /** The picture pushed over ADB, or null. */
    static File custom(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        for (String n : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, n);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Internal storage/Wallpapers: your own pictures. */
    static File folder() {
        return new File(Environment.getExternalStorageDirectory(), "Wallpapers");
    }

    /** The pack pictures downloaded so far (they join Rotate and Weather). */
    static List<File> packPictures(Context ctx) {
        List<File> out = new ArrayList<>();
        for (Photo p : BUILT_IN) if (p.pack && isCached(ctx, p.id)) out.add(cached(ctx, p.id));
        return out;
    }

    /** Pictures in the Wallpapers folder, A–Z. Creates the folder so it shows up in Files. */
    static List<File> userPictures() {
        File dir = folder();
        if (!dir.isDirectory()) dir.mkdirs();
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".png") || n.endsWith(".webp"))) {
                out.add(f);
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** The file for a wallpaper choice, or null (none, not downloaded yet, no pushed picture). */
    static File file(Context ctx, String choice) {
        if (CUSTOM.equals(choice)) return custom(ctx);
        if (BING.equals(choice)) return Bing.shown(ctx);
        if (ROTATE.equals(choice) || WEATHER.equals(choice)) return rotating(ctx, choice);
        if (choice.startsWith(FILE)) {
            String name = choice.substring(FILE.length());
            File f = new File(folder(), name);
            return !name.contains("/") && f.isFile() ? f : null;
        }
        if (photo(choice) == null) return null;
        File f = cached(ctx, choice);
        return f.isFile() ? f : null;
    }

    /** Downloads a built-in into the cache. Blocking: call off the main thread. */
    static synchronized boolean fetch(Context ctx, String id) {
        Photo p = photo(id);
        if (p == null) return false;
        File out = cached(ctx, id);
        if (out.isFile()) return true;
        File dir = out.getParentFile();
        // Photos dropped from the list in an update: free their space.
        File[] old = dir == null ? null : dir.listFiles();
        if (old != null) for (File f : old) {
            String n = f.getName();
            if (photo(n.endsWith(".jpg") ? n.substring(0, n.length() - 4) : "") == null) f.delete();
        }
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(out.getPath() + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(p.url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", UA);
            String type = c.getContentType();
            if (c.getResponseCode() != 200 || type == null || !type.startsWith("image/")) return false;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
            }
            return tmp.renameTo(out);
        } catch (Exception e) {
            android.util.Log.w("TvHome", "wallpaper " + id + " download failed", e);
            return false;
        } finally {
            if (c != null) c.disconnect();
            if (tmp.isFile()) tmp.delete();
        }
    }
}
