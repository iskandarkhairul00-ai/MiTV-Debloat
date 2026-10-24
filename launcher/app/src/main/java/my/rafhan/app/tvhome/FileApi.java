package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The phone remote's Files tab: browse the TV's storage (internal + mounted USB drives),
 * download, upload (streamed straight to disk) and open files on the TV.
 *
 * Every path is canonicalised and must sit inside one of the storage roots, so "../" tricks or
 * absolute paths elsewhere (/data, /system) are refused. Storage access comes from
 * READ/WRITE_EXTERNAL_STORAGE (granted by build-and-install.sh) with
 * requestLegacyExternalStorage, which Android 10 honours.
 */
final class FileApi {
    private static final int MAX_ENTRIES = 2000;
    /** Sanity cap on an upload, on top of the free-space check. */
    private static final long MAX_UPLOAD = 16L * 1024 * 1024 * 1024;

    private final Context ctx;

    FileApi(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Whether the Files tab can read the TV's shared storage. Android 10 uses READ/WRITE with
     * requestLegacyExternalStorage; Android 11+ replaced those with scoped storage and needs
     * "All files access" (MANAGE_EXTERNAL_STORAGE), granted over ADB by build-and-install.sh.
     */
    static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    static final class Root {
        final String name;
        final File dir;

        Root(String name, File dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    /** Internal storage first, then any mounted removable volume. */
    List<Root> roots() {
        List<Root> out = new ArrayList<>();
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                String state = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                // StorageVolume.getDirectory() is API 30; on Android 10 removable volumes live at /storage/<uuid>.
                File dir = v.isPrimary() ? Environment.getExternalStorageDirectory()
                        : v.getUuid() != null ? new File("/storage/" + v.getUuid()) : null;
                if (dir == null || !dir.isDirectory()) continue;
                String name = v.isPrimary() ? "Internal storage" : v.getDescription(ctx);
                out.add(new Root(name, canonical(dir)));
            }
        }
        if (out.isEmpty()) out.add(new Root("Internal storage", canonical(Environment.getExternalStorageDirectory())));
        return out;
    }

    JSONObject rootsJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Root r : roots()) {
            arr.put(new JSONObject().put("name", r.name).put("path", r.dir.getPath()).put("free", new StatFs(r.dir.getPath()).getAvailableBytes()));
        }
        // Shortcut to the folder the TV's Settings → Wallpaper list reads.
        File walls = Wallpapers.folder();
        if (!walls.isDirectory()) walls.mkdirs();
        JSONArray shortcuts = new JSONArray();
        if (walls.isDirectory()) {
            shortcuts.put(new JSONObject().put("name", "Wallpapers").put("path", canonical(walls).getPath())
                    .put("hint", "Pictures here appear in the TV's wallpaper list"));
        }
        return new JSONObject().put("ok", true).put("roots", arr).put("shortcuts", shortcuts)
                .put("access", hasAllFilesAccess());
    }

    /** {path, root, rootPath, parent, entries:[{name, dir, size, mtime}]}; null if the path isn't allowed. */
    JSONObject list(String path) throws JSONException {
        File dir = resolve(path);
        Root root = rootOf(dir);
        if (dir == null || root == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return new JSONObject().put("ok", false).put("error", "The TV won't let us read this folder.");
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        JSONArray entries = new JSONArray();
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (entries.length() >= MAX_ENTRIES) break;
            entries.put(new JSONObject().put("name", f.getName()).put("dir", f.isDirectory())
                    .put("size", f.isDirectory() ? 0 : f.length()).put("mtime", f.lastModified()));
        }
        JSONObject o = new JSONObject().put("ok", true).put("path", dir.getPath())
                .put("root", root.name).put("rootPath", root.dir.getPath()).put("entries", entries);
        if (!dir.equals(root.dir)) o.put("parent", dir.getParentFile().getPath());
        return o;
    }

    /** The file if it's an allowed, readable regular file. */
    File file(String path) {
        File f = resolve(path);
        return f != null && f.isFile() && f.canRead() ? f : null;
    }

    /** Streams a file as a download, honouring a single "bytes=" Range so players can seek. */
    void download(File f, OutputStream out, String range) throws IOException {
        long size = f.length();
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null) {
            long[] r = parseRange(range, size);
            if (r != null) {
                start = r[0];
                end = r[1];
                partial = true;
            }
        }
        long length = end - start + 1;
        StringBuilder head = new StringBuilder()
                .append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(mime(f, "application/octet-stream")).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Accept-Ranges: bytes\r\n");
        if (partial) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        }
        head.append("Content-Disposition: attachment; filename*=UTF-8''")
                .append(URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20")).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        try (InputStream in = new FileInputStream(f)) {
            for (long skip = start; skip > 0; ) {
                long n = in.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            byte[] buf = new byte[64 * 1024];
            for (long left = length; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    /** A single "bytes=a-b" / "bytes=a-" / "bytes=-n" range; null (send the whole file) otherwise. */
    private static long[] parseRange(String header, long size) {
        if (size <= 0 || header == null) return null;
        String v = header.trim();
        if (!v.startsWith("bytes=")) return null;
        v = v.substring(6).trim();
        if (v.isEmpty() || v.contains(",")) return null; // multi-range: fall back to the whole file
        int dash = v.indexOf('-');
        if (dash < 0) return null;
        try {
            String a = v.substring(0, dash).trim(), b = v.substring(dash + 1).trim();
            long start, end;
            if (a.isEmpty()) {
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, size - n);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
            }
            if (start < 0 || start > end) return null;
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Writes exactly {@code length} bytes from {@code in} into {@code dir/name} (via a hidden
     * .part file, renamed when complete). Returns {code, json}.
     */
    Object[] upload(String dirPath, String rawName, long length, InputStream in) throws JSONException {
        File dir = resolve(dirPath);
        if (dir == null || !dir.isDirectory()) return result(403, "Choose a folder on the TV first.");
        if (length < 0) return result(411, "Upload needs a Content-Length.");
        if (length > MAX_UPLOAD) return result(413, "That file is too big to upload.");
        String name = cleanName(rawName);
        if (name.isEmpty()) return result(400, "Missing file name.");
        long free = new StatFs(dir.getPath()).getAvailableBytes();
        if (length > free - 20L * 1024 * 1024) return result(507, "Not enough space on the TV (" + human(free) + " free).");

        File target = unique(dir, name);
        File part = new File(dir, "." + target.getName() + ".part");
        long written = 0;
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            while (written < length) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - written));
                if (n < 0) break;
                out.write(buf, 0, n);
                written += n;
            }
        } catch (IOException e) {
            part.delete();
            return result(500, dir.getPath().startsWith("/storage/emulated")
                    ? "Couldn't write the file." : "Android 10 doesn't allow writing to this USB drive.");
        }
        if (written != length || !part.renameTo(target)) {
            part.delete();
            return result(500, "Upload was interrupted.");
        }
        return new Object[] {200, new JSONObject().put("ok", true).put("name", target.getName())};
    }

    // Used when there's no "Always" default, so the phone never lands on Android's "Open with"
    // chooser (the phone's D-pad can't reach it outside the home screen).
    private static final String[] VIDEO_APPS = {"com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad", "com.mitv.videoplayer"};
    private static final String[] IMAGE_APPS = {"com.lonelycatgames.Xplore", "com.mitv.gallery", "com.mixplorer"};

    /**
     * VIEW intent for the file through FileProvider, aimed at one app: the user's default if
     * they ticked "Always", else a preferred player/viewer, else the first app that can open it.
     */
    Intent openIntent(File f) {
        String type = mime(f, null);
        if (type == null) return null;
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", f);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(view, 0);
        if (apps.isEmpty()) return null;
        ResolveInfo def = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        boolean chooser = def == null || "android".equals(def.activityInfo.packageName)
                || def.activityInfo.name.endsWith("ResolverActivity");
        if (!chooser) return view;
        String[] preferred = type.startsWith("image/") ? IMAGE_APPS : VIDEO_APPS;
        for (String pkg : preferred) {
            for (ResolveInfo ri : apps) {
                if (ri.activityInfo.packageName.equals(pkg)) {
                    return view.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                }
            }
        }
        ResolveInfo first = apps.get(0);
        return view.setClassName(first.activityInfo.packageName, first.activityInfo.name);
    }

    // ---------------------------------------------------------------- helpers

    private File resolve(String path) {
        if (path == null || path.isEmpty()) return null;
        File f = canonical(new File(path));
        return rootOf(f) != null ? f : null;
    }

    private Root rootOf(File f) {
        if (f == null) return null;
        for (Root r : roots()) {
            if (f.equals(r.dir) || f.getPath().startsWith(r.dir.getPath() + "/")) return r;
        }
        return null;
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static String cleanName(String raw) {
        if (raw == null) return "";
        String n = raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        while (n.startsWith(".")) n = n.substring(1);
        return n.replaceAll("[\\x00-\\x1f]", "");
    }

    /** "name.ext", or "name (1).ext" ... if taken. */
    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static String mime(File f, String fallback) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return fallback;
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type != null ? type : fallback;
    }

    static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return (bytes >> 10) + " KB";
    }

    private static Object[] result(int code, String error) throws JSONException {
        return new Object[] {code, new JSONObject().put("ok", false).put("error", error)};
    }
}
