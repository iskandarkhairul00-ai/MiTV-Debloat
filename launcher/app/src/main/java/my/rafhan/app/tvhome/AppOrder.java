package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Susunan app dalam grid di bawah dock yang kekal. Setiap app ingat tempatnya: app tak bergerak
 * bila app lain baru dipakai, dipasang atau dibuang. App baru diletak di hujung (ikut turutan
 * asal, A-Z). App yang dibuang atau dimasukkan ke dock simpan tempatnya, jadi kembali ke tempat
 * lama bila ia muncul semula.
 */
final class AppOrder {
    private static final String PREFS = "app_order";
    private static final String KEY = "keys";
    private static final int MAX_REMEMBERED = 1500;

    private AppOrder() {}

    /** Susun {@code list} (ikut cacheKey) mengikut tempat yang diingat; simpan kalau ada yang baru. */
    static void apply(Context ctx, List<AppEntry> list) {
        if (list.isEmpty()) return; // senarai kosong sementara (cth. waktu boot): jangan ubah apa-apa
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> saved = decode(sp.getString(KEY, ""));
        List<String> present = new ArrayList<>(list.size());
        for (AppEntry e : list) present.add(e.cacheKey);

        List<String> seq = merge(saved, present);
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < seq.size(); i++) pos.put(seq.get(i), i);
        list.sort((a, b) -> Integer.compare(pos.get(a.cacheKey), pos.get(b.cacheKey)));

        if (!seq.equals(saved)) sp.edit().putString(KEY, encode(seq)).apply();
    }

    /**
     * Turutan penuh yang diingat: yang lama kekal pada tempatnya (termasuk yang tak hadir
     * sekarang), yang baru ditambah di hujung ikut turutan {@code present}.
     */
    static List<String> merge(List<String> saved, List<String> present) {
        List<String> seq = new ArrayList<>(saved);
        Set<String> known = new HashSet<>(saved);
        for (String k : present) if (known.add(k)) seq.add(k);
        if (seq.size() > MAX_REMEMBERED) { // buang yang tak hadir dahulu, jangan sentuh yang ada
            Set<String> here = new HashSet<>(present);
            List<String> trimmed = new ArrayList<>();
            int drop = seq.size() - MAX_REMEMBERED;
            for (String k : seq) {
                if (drop > 0 && !here.contains(k)) drop--;
                else trimmed.add(k);
            }
            seq = trimmed;
        }
        return seq;
    }

    private static List<String> decode(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private static String encode(List<String> seq) {
        return String.join("\n", seq);
    }
}
