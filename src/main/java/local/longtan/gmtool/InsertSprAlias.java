package local.longtan.gmtool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InsertSprAlias {
    public static void main(String[] args) throws Exception {
        Path idx = Path.of(args[0]);
        int srcGfx = Integer.parseInt(args[1]);
        int dstGfx = Integer.parseInt(args[2]);
        byte[] raw = Files.readAllBytes(idx);
        int declared = i32(raw, 0);
        int records = Math.min(declared, (raw.length - 4) / 28);
        List<Rec> list = new ArrayList<>(records + 256);
        Map<Integer, Rec> srcByN = new HashMap<>();
        Map<String, Rec> byName = new HashMap<>();
        for (int i = 0; i < records; i++) {
            Rec rec = Rec.read(raw, 4 + i * 28);
            list.add(rec);
            byName.put(rec.name.toLowerCase(Locale.ROOT), rec);
            Integer n = sprIndex(rec.name, srcGfx);
            if (n != null) srcByN.put(n, rec);
        }
        int added = 0;
        int skipped = 0;
        for (Map.Entry<Integer, Rec> e : srcByN.entrySet()) {
            String destName = dstGfx + "-" + e.getKey() + ".spr";
            if (byName.containsKey(destName.toLowerCase(Locale.ROOT))) {
                skipped++;
                continue;
            }
            Rec alias = new Rec(e.getValue().offset, destName, e.getValue().size);
            list.add(alias);
            byName.put(destName.toLowerCase(Locale.ROOT), alias);
            added++;
        }
        list.sort(Comparator.comparing(r -> r.name.toLowerCase(Locale.ROOT)));
        ByteBuffer out = ByteBuffer.allocate(4 + list.size() * 28).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(list.size());
        for (Rec rec : list) rec.write(out);
        Files.write(idx, out.array());
        System.out.println("idx=" + idx);
        System.out.println("src=" + srcGfx + " dst=" + dstGfx);
        System.out.println("old=" + records + " new=" + list.size() + " added=" + added + " skipped_existing=" + skipped);
        for (int n : new int[] {0, 4, 20, 40, 46, 50, 54, 58, 62, 66}) {
            Rec dest = byName.get((dstGfx + "-" + n + ".spr").toLowerCase(Locale.ROOT));
            Rec src = srcByN.get(n);
            System.out.println("check " + dstGfx + "-" + n + ".spr dest="
                    + (dest == null ? "MISSING" : ("off=" + dest.offset + " size=" + dest.size))
                    + " src=" + (src == null ? "MISSING" : ("off=" + src.offset + " size=" + src.size)));
        }
    }

    private static Integer sprIndex(String name, int gfx) {
        String prefix = gfx + "-";
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(prefix) || !lower.endsWith(".spr")) return null;
        String mid = name.substring(prefix.length(), name.length() - 4);
        if (mid.isEmpty()) return null;
        for (int i = 0; i < mid.length(); i++) if (!Character.isDigit(mid.charAt(i))) return null;
        return Integer.valueOf(mid);
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private static final class Rec {
        final int offset;
        final String name;
        final int size;

        Rec(int offset, String name, int size) {
            this.offset = offset;
            this.name = name;
            this.size = size;
        }

        static Rec read(byte[] raw, int s) {
            int offset = i32(raw, s);
            int n = 0;
            while (n < 20 && raw[s + 4 + n] != 0) n++;
            String name = new String(raw, s + 4, n, StandardCharsets.US_ASCII).trim();
            int size = i32(raw, s + 24);
            return new Rec(offset, name, size);
        }

        void write(ByteBuffer out) {
            out.putInt(offset);
            byte[] nb = new byte[20];
            byte[] src = name.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(src, 0, nb, 0, Math.min(20, src.length));
            out.put(nb);
            out.putInt(size);
        }
    }
}
