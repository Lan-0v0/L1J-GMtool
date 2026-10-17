package local.longtan.gmtool;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 现用客户端 Text.pak：国服头顶用 desc-h.tbl（GBK 简体），按 $nameid 现查。 */
final class ClientStrings {
    private final String[] byId;

    static ClientStrings load(Path clientRoot) {
        try {
            Path idx = clientRoot.resolve("Text.idx");
            Path pak = clientRoot.resolve("Text.pak");
            if (!Files.isRegularFile(idx) || !Files.isRegularFile(pak)) {
                return new ClientStrings(new String[0]);
            }
            byte[] idxRaw = Files.readAllBytes(idx);
            int declared = i32(idxRaw, 0);
            byte[] body = PakCodec.decode(idxRaw, 4);
            int records = Math.min(declared, body.length / 28);
            int off = -1, size = 0;
            int offC = -1, sizeC = 0;
            for (int i = 0; i < records; i++) {
                int s = i * 28;
                String name = zname(body, s + 4, 20).toLowerCase();
                if ("desc-h.tbl".equals(name)) {
                    off = i32(body, s);
                    size = i32(body, s + 24);
                } else if ("desc-c.tbl".equals(name)) {
                    offC = i32(body, s);
                    sizeC = i32(body, s + 24);
                }
            }
            if (off < 0) {
                off = offC;
                size = sizeC;
            }
            if (off < 0 || size <= 0) return new ClientStrings(new String[0]);
            byte[] pakRaw = Files.readAllBytes(pak);
            if (off + (long) size > pakRaw.length) return new ClientStrings(new String[0]);
            byte[] file = new byte[size];
            System.arraycopy(pakRaw, off, file, 0, size);
            byte[] dec = PakCodec.decode(file, 0);
            String[] decoded = decodeText(dec);
            return new ClientStrings(parseTbl(decoded[1]));
        } catch (Exception e) {
            return new ClientStrings(new String[0]);
        }
    }

    private ClientStrings(String[] byId) {
        this.byId = byId;
    }

    String resolve(String nameid, String name) {
        Integer id = dollarId(nameid);
        if (id != null && id >= 0 && id < byId.length) {
            String s = displayPart(byId[id]);
            if (!s.isEmpty()) return s;
        }
        String literal = stripCodes(nameid);
        if (!literal.isEmpty() && !literal.startsWith("$")) return literal;
        return stripCodes(name);
    }

    List<Integer> idsMatching(String query) {
        List<Integer> ids = new ArrayList<>();
        if (query == null || query.isEmpty() || byId.length == 0) return ids;
        String q = query.trim();
        if (q.isEmpty()) return ids;
        for (int i = 0; i < byId.length && ids.size() < 80; i++) {
            if (displayPart(byId[i]).contains(q) || byId[i].contains(q)) ids.add(i);
        }
        return ids;
    }

    int size() {
        return byId.length;
    }

    private static String[] parseTbl(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length < 2) return new String[0];
        int skip = 0;
        try {
            Integer.parseInt(lines[0].trim());
            skip = 1;
        } catch (NumberFormatException ignored) {
        }
        String[] out = new String[Math.max(0, lines.length - skip)];
        for (int i = 0; i < out.length; i++) out[i] = lines[i + skip];
        return out;
    }

    private static String[] decodeText(byte[] data) {
        String gbk = new String(data, Charset.forName("GBK"));
        String utf = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        String big5 = new String(data, Charset.forName("Big5"));
        int scoreG = score(gbk), scoreU = score(utf), scoreB = score(big5);
        if (scoreU >= scoreG && scoreU >= scoreB && scoreU > 0) return new String[] {"utf8", utf};
        if (scoreG >= scoreB) return new String[] {"gbk", gbk};
        return new String[] {"big5", big5};
    }

    private static int score(String s) {
        int n = 0;
        if (s.contains("地灵")) n += 80;
        if (s.contains("吉伦")) n += 80;
        if (s.contains("青蛙")) n += 40;
        if (s.contains("弱化欧熊")) n += 40;
        if (s.contains("稻草人")) n += 20;
        if (s.contains("地靈") || s.contains("吉倫") || s.contains("歐熊")) n -= 40;
        return n;
    }

    private static String displayPart(String s) {
        if (s == null) return "";
        int hat = s.lastIndexOf('^');
        if (hat >= 0 && hat + 1 < s.length()) s = s.substring(hat + 1);
        return stripCodes(s);
    }

    static String stripCodes(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("\\\\f.", "").replace('\u3000', ' ').trim();
    }

    private static Integer dollarId(String nameid) {
        if (nameid == null) return null;
        String t = nameid.trim();
        if (t.length() < 2 || t.charAt(0) != '$') return null;
        try {
            return Integer.parseInt(t.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private static String zname(byte[] b, int o, int n) {
        int end = o;
        while (end < o + n && b[end] != 0) end++;
        return new String(b, o, Math.max(0, end - o), java.nio.charset.StandardCharsets.ISO_8859_1).trim();
    }
}
