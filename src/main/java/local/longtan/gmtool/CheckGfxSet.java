package local.longtan.gmtool;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CheckGfxSet {
    public static void main(String[] args) throws Exception {
        Path client = Path.of(args[0]);
        int gfx = Integer.parseInt(args[1]);
        Pattern p = Pattern.compile("^" + gfx + "-(\\d+)\\.spr$", Pattern.CASE_INSENSITIVE);
        TreeMap<Integer, Integer> sizes = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(client, "Sprite*.idx")) {
            for (Path idx : stream) {
                byte[] raw = Files.readAllBytes(idx);
                if (raw.length < 32) continue;
                int declared = i32(raw, 0);
                scan(raw, 4, declared, p, sizes);
                if (sizes.isEmpty()) scan(PakCodec.decode(raw, 4), 0, declared, p, sizes);
            }
        }
        System.out.println("gfx=" + gfx + " spr_count=" + sizes.size());
        if (sizes.isEmpty()) return;
        System.out.println("min=" + sizes.firstKey() + " max=" + sizes.lastKey());
        TreeSet<Integer> missing = new TreeSet<>();
        for (int i = sizes.firstKey(); i <= sizes.lastKey(); i++) {
            if (!sizes.containsKey(i)) missing.add(i);
        }
        System.out.println("holes_in_range=" + missing);
        int[] weapons = {0, 4, 11, 20, 24, 40, 46, 50, 54, 58, 62, 66};
        for (int w : weapons) {
            System.out.println("weapon " + w + (sizes.containsKey(w) ? (" size=" + sizes.get(w)) : " MISSING"));
        }
    }

    private static void scan(byte[] body, int base, int declared, Pattern p, TreeMap<Integer, Integer> sizes) {
        int records = Math.min(declared, (body.length - base) / 28);
        for (int i = 0; i < records; i++) {
            int s = base + i * 28;
            int n = 0;
            while (n < 20 && s + 4 + n < body.length && body[s + 4 + n] != 0) n++;
            String name = new String(body, s + 4, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
            Matcher m = p.matcher(name.toLowerCase(Locale.ROOT));
            if (!m.matches()) continue;
            sizes.put(Integer.parseInt(m.group(1)), i32(body, s + 24));
        }
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }
}
