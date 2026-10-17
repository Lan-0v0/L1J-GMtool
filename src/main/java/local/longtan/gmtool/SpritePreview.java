package local.longtan.gmtool;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inventory icons from client `.tbt` (and small `.img` fallback). Does not decode scene SPR. */
final class SpritePreview {
    private static final Pattern NAME = Pattern.compile(
            "(\\d+)(?:-(\\d+))?\\.(img|tbt)$", Pattern.CASE_INSENSITIVE);
    private final Path clientRoot;
    private final Map<Integer, List<Entry>> byId = new HashMap<>();
    private final ConcurrentHashMap<Integer, IconHit> iconCache = new ConcurrentHashMap<>();

    SpritePreview(Path home) throws IOException {
        String override = System.getProperty("gmtool.client.dir", "").trim();
        Path root = override.isEmpty()
                ? home.resolve("..").resolve("龙潭天堂").normalize()
                : Path.of(override).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            // 客户端目录只用于图标预览；缺失时 GM 台其余功能照常可用
            this.clientRoot = null;
            System.out.println("[i] 客户端目录不存在，图标预览已禁用：" + root);
            return;
        }
        this.clientRoot = root;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(clientRoot, "Sprite*.idx")) {
            for (Path idx : stream) {
                indexPack(idx);
            }
        }
    }

    Map<String, Object> icon(int invgfx) throws IOException {
        IconHit hit = loadIcon(invgfx);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invgfx", invgfx);
        result.put("file", hit.file);
        result.put("pack", hit.pack);
        result.put("width", hit.width);
        result.put("height", hit.height);
        result.put("png", java.util.Base64.getEncoder().encodeToString(hit.png));
        return result;
    }

    private IconHit loadIcon(int id) throws IOException {
        if (clientRoot == null) {
            throw new IOException("未配置客户端目录，图标预览不可用（用 -Dgmtool.client.dir 指定）");
        }
        IconHit cached = iconCache.get(id);
        if (cached != null) return cached;
        // Inventory icons in this client live in .tbt (sparse RGB555 runs,
        // possibly several spans per row). Same-number .img/.png/.gif are
        // often UI or portraits — 武士刀 invgfx=58 is 58.tbt, while 58.img is Exit.
        Entry tbt = findExact(id, ".tbt", true);
        if (tbt == null) tbt = findExact(id, ".tbt", false);
        if (tbt != null) {
            BufferedImage image = decodeTbt(extract(tbt));
            if (isInventoryIcon(image)) {
                IconHit hit = new IconHit(tbt.name, tbt.idx.getFileName().toString(),
                        image.getWidth(), image.getHeight(), iconCanvas(image));
                rememberIcon(id, hit);
                return hit;
            }
        }
        Entry img = findExact(id, ".img", true);
        if (img == null) img = findExact(id, ".img", false);
        if (img != null) {
            BufferedImage image = decodeImg(extract(img));
            if (isInventoryIcon(image) && !looksLikeUiStrip(image)) {
                IconHit hit = new IconHit(img.name, img.idx.getFileName().toString(),
                        image.getWidth(), image.getHeight(), iconCanvas(image));
                rememberIcon(id, hit);
                return hit;
            }
        }
        IconHit miss = new IconHit("", "", 64, 64, placeholder());
        rememberIcon(id, miss);
        return miss;
    }

    private Entry findExact(int id, String extension, boolean mainPackOnly) {
        List<Entry> entries = byId.get(id);
        if (entries == null) return null;
        String wanted = id + extension.toLowerCase(Locale.ROOT);
        Entry fallback = null;
        for (Entry entry : entries) {
            if (!entry.name.toLowerCase(Locale.ROOT).equals(wanted)) continue;
            if (mainPackOnly && !entry.mainPack) {
                if (fallback == null) fallback = entry;
                continue;
            }
            return entry;
        }
        return mainPackOnly ? null : fallback;
    }

    private byte[] extract(Entry entry) throws IOException {
        if (entry == null) return new byte[0];
        try (RandomAccessFile raf = new RandomAccessFile(entry.pak.toFile(), "r")) {
            if (entry.offset < 0 || entry.size <= 0 || entry.offset + (long) entry.size > raf.length()) {
                return new byte[0];
            }
            byte[] data = new byte[entry.size];
            raf.seek(entry.offset);
            raf.readFully(data);
            return data;
        }
    }

    private void indexPack(Path idx) throws IOException {
        Path pak = pakPath(idx);
        if (!Files.isRegularFile(pak)) return;
        boolean mainPack = idx.getFileName().toString().equalsIgnoreCase("Sprite.idx");
        byte[] raw = Files.readAllBytes(idx);
        if (raw.length < 32) return;
        int declared = i32(raw, 0);
        byte[] body = slice(raw, 4);
        if (!indexBody(idx, pak, body, declared, mainPack)) {
            indexBody(idx, pak, PakCodec.decode(raw, 4), declared, mainPack);
        }
    }

    private boolean indexBody(Path idx, Path pak, byte[] body, int declared, boolean mainPack) {
        int records = Math.min(declared, body.length / 28);
        int hits = 0;
        for (int i = 0; i < records; i++) {
            int s = i * 28;
            int offset = i32(body, s);
            String name = zname(body, s + 4, 20);
            int size = i32(body, s + 24);
            Matcher matcher = NAME.matcher(name);
            if (!matcher.matches()) continue;
            hits++;
            int id = Integer.parseInt(matcher.group(1));
            byId.computeIfAbsent(id, key -> new ArrayList<>())
                    .add(new Entry(idx, pak, offset, size, name, mainPack));
        }
        return hits > 0;
    }

    private static BufferedImage decodeImg(byte[] data) {
        return decodeRgb(data, 0);
    }

    private static BufferedImage decodeTbt(byte[] data) {
        if (data == null || data.length < 8) return null;
        int y0 = data[1] & 255;
        int canvas = 96;
        BufferedImage image = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        int p = 4;
        int y = y0;
        int painted = 0;
        int rows = 0;
        while (p < data.length && y < canvas && rows < 80) {
            int spans = data[p++] & 255;
            if (spans == 0) {
                y++;
                rows++;
                continue;
            }
            if (spans > 16) break;
            boolean bad = false;
            for (int s = 0; s < spans; s++) {
                if (p + 2 > data.length) {
                    bad = true;
                    break;
                }
                int x = data[p++] & 255;
                int n = data[p++] & 255;
                if (n == 0 || n > 64 || p + n * 2 > data.length) {
                    bad = true;
                    break;
                }
                for (int i = 0; i < n; i++) {
                    int v = u16(data, p);
                    p += 2;
                    int xx = x + i;
                    if (xx >= 0 && xx < canvas && y >= 0 && y < canvas && v != 0) {
                        image.setRGB(xx, y, rgb555Icon(v));
                        painted++;
                    }
                }
            }
            if (bad) break;
            y++;
            rows++;
        }
        if (painted < 8) return null;
        return thicken(cropOpaque(image));
    }

    private static BufferedImage thicken(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int opaque = opaqueCount(src);
        if (w * h == 0 || opaque * 5 > w * h) return src;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        int[] dst = px.clone();
        int[] dx = { -1, 1, 0, 0, -1, -1, 1, 1 };
        int[] dy = { 0, 0, -1, 1, -1, 1, -1, 1 };
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((px[i] >>> 24) > 8) continue;
                for (int k = 0; k < 8; k++) {
                    int xx = x + dx[k], yy = y + dy[k];
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    int c = px[yy * w + xx];
                    if ((c >>> 24) > 8) {
                        dst[i] = c;
                        break;
                    }
                }
            }
        }
        out.setRGB(0, 0, w, h, dst, 0, w);
        return out;
    }

    private static BufferedImage cropOpaque(BufferedImage src) {
        int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if ((src.getRGB(x, y) >>> 24) > 8) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX) return src;
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, minX, minY, maxX + 1, maxY + 1, null);
        g.dispose();
        return out;
    }

    private static boolean isInventoryIcon(BufferedImage image) {
        if (image == null) return false;
        int w = image.getWidth();
        int h = image.getHeight();
        int opaque = opaqueCount(image);
        return w >= 4 && h >= 4 && w <= 64 && h <= 64 && opaque >= 12;
    }

    private static boolean looksLikeUiStrip(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return w > h * 2 && h <= 24;
    }

    private static byte[] iconCanvas(BufferedImage source) throws IOException {
        int max = Math.max(1, Math.max(source.getWidth(), source.getHeight()));
        int scale = Math.max(3, (64 + max - 1) / max);
        int w = Math.max(1, source.getWidth() * scale);
        int h = Math.max(1, source.getHeight() * scale);
        int canvasSize = Math.max(64, Math.max(w, h) + 8);
        BufferedImage canvas = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, (canvasSize - w) / 2, (canvasSize - h) / 2, w, h, null);
        g.dispose();
        return toPng(canvas);
    }

    private static int opaqueCount(BufferedImage image) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) n++;
            }
        }
        return n;
    }

    private static BufferedImage decodeRgb(byte[] data, int offset) {
        if (data == null || offset + 8 > data.length) return null;
        int w = u16(data, offset);
        int h = u16(data, offset + 2);
        int hdr = 8;
        if (w < 1 || w > 1024 || h < 1 || h > 1024) return null;
        if (offset + hdr + w * h * 2 > data.length) {
            hdr = 4;
            if (offset + hdr + w * h * 2 > data.length) return null;
        }
        return decodeRgbAt(data, offset + hdr, w, h);
    }

    private static BufferedImage decodeRgbAt(byte[] data, int pos, int w, int h) {
        if (pos + w * h * 2 > data.length) return null;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int p = pos;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (data[p] & 255) | ((data[p + 1] & 255) << 8);
                p += 2;
                image.setRGB(x, y, rgb555(v));
            }
        }
        return image;
    }

    static int rgb555(int v) {
        if (v == 0 || v == 0x8000) return 0;
        return rgb555Icon(v);
    }

    private static int rgb555Icon(int v) {
        int r = ((v >> 10) & 31) * 255 / 31;
        int g = ((v >> 5) & 31) * 255 / 31;
        int b = (v & 31) * 255 / 31;
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private void rememberIcon(int id, IconHit hit) {
        if (iconCache.size() > 800) iconCache.clear();
        iconCache.put(id, hit);
    }

    private static byte[] placeholder() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(0x24, 0x28, 0x2f));
        g.fillRect(0, 0, 64, 64);
        g.setColor(new java.awt.Color(0x3a, 0x40, 0x4a));
        g.drawRect(1, 1, 61, 61);
        g.dispose();
        return toPng(image);
    }

    private static Path pakPath(Path idx) {
        String name = idx.getFileName().toString();
        String stem = name.substring(0, name.length() - 4);
        Path lower = idx.resolveSibling(stem + ".pak");
        if (Files.isRegularFile(lower)) return lower;
        return idx.resolveSibling(stem + ".Pak");
    }

    private static byte[] slice(byte[] src, int offset) {
        byte[] out = new byte[src.length - offset];
        System.arraycopy(src, offset, out, 0, out.length);
        return out;
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8);
    }

    private static String zname(byte[] b, int o, int n) {
        int end = o;
        while (end < o + n && b[end] != 0) end++;
        return new String(b, o, Math.max(0, end - o), StandardCharsets.ISO_8859_1).trim();
    }

    private static final class Entry {
        final Path idx;
        final Path pak;
        final int offset;
        final int size;
        final String name;
        final boolean mainPack;

        Entry(Path idx, Path pak, int offset, int size, String name, boolean mainPack) {
            this.idx = idx;
            this.pak = pak;
            this.offset = offset;
            this.size = size;
            this.name = name;
            this.mainPack = mainPack;
        }
    }

    private static final class IconHit {
        final String file;
        final String pack;
        final int width;
        final int height;
        final byte[] png;

        IconHit(String file, String pack, int width, int height, byte[] png) {
            this.file = file;
            this.pack = pack;
            this.width = width;
            this.height = height;
            this.png = png;
        }
    }
}
