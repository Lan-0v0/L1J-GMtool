package local.longtan.gmtool;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MapTiles {
    private static final int ORIGIN = 32768;
    private static final int BLOCK = 64;
    private static final int CELLS = BLOCK * BLOCK;
    private static final int S32_CELL = 12;
    private static final int SEG_CELL = 6;
    private static final int S32_BYTES = CELLS * S32_CELL;

    private final Path clientRoot;
    private final Path serverMaps;
    private final Path cacheDir;
    private final Map<Integer, TilFile> tils = new HashMap<>();
    private final ConcurrentHashMap<Integer, int[]> tilColors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, byte[]> pngCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, int[]> blockCache = new ConcurrentHashMap<>();

    MapTiles(Path home) throws IOException {
        this.clientRoot = home.resolve("..").resolve("龙潭天堂").normalize();
        this.serverMaps = home.resolve("..").resolve("服务端").resolve("maps").normalize();
        this.cacheDir = home.resolve("data").resolve("maps");
        Files.createDirectories(cacheDir);
        Path idx = clientRoot.resolve("Tile.idx");
        if (Files.isRegularFile(idx)) {
            indexTiles(idx);
        }
    }

    Map<String, Object> meta(int mapId, int startX, int startY, int endX, int endY, String name) {
        Bounds bounds = resolveBounds(mapId, startX, startY, endX, endY);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mapId", mapId);
        result.put("name", name == null ? ("地图 " + mapId) : name);
        result.put("startX", bounds.startX);
        result.put("startY", bounds.startY);
        result.put("endX", bounds.endX);
        result.put("endY", bounds.endY);
        result.put("width", bounds.width());
        result.put("height", bounds.height());
        result.put("giranX", mapId == 4 ? 33440 : (bounds.startX + bounds.endX) / 2);
        result.put("giranY", mapId == 4 ? 32800 : (bounds.startY + bounds.endY) / 2);
        result.put("source", bounds.source);
        return result;
    }

    byte[] png(int mapId, int startX, int startY, int endX, int endY) throws IOException {
        return png(mapId, startX, startY, endX, endY, false);
    }

    byte[] png(int mapId, int startX, int startY, int endX, int endY, boolean force) throws IOException {
        Path disk = cacheDir.resolve(mapId + ".png");
        if (!force) {
            byte[] cached = pngCache.get(mapId);
            if (cached != null) return cached;
            if (Files.isRegularFile(disk) && Files.size(disk) > 128) {
                byte[] bytes = Files.readAllBytes(disk);
                pngCache.put(mapId, bytes);
                return bytes;
            }
        }
        Bounds bounds = resolveBounds(mapId, startX, startY, endX, endY);
        byte[] bytes = render(mapId, bounds);
        pngCache.put(mapId, bytes);
        try {
            Files.write(disk, bytes);
        } catch (IOException ignored) {
        }
        return bytes;
    }

    void invalidateAll() {
        pngCache.clear();
        blockCache.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.png")) {
            for (Path path : stream) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    boolean inBounds(int mapId, int x, int y, int startX, int startY, int endX, int endY) {
        Bounds bounds = resolveBounds(mapId, startX, startY, endX, endY);
        return x >= bounds.startX && x <= bounds.endX && y >= bounds.startY && y <= bounds.endY;
    }

    private byte[] render(int mapId, Bounds bounds) throws IOException {
        int width = Math.max(1, bounds.width());
        int height = Math.max(1, bounds.height());
        width = Math.min(width, 4096);
        height = Math.min(height, 4096);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Path dir = clientRoot.resolve("map").resolve(Integer.toString(mapId));
        boolean painted = false;
        // Tile.Pak scanlines still produce horizontal striping; keep the
        // per-map walkable layer until the native 24x24 decoder is confirmed.
        if (false && Files.isDirectory(dir) && !tils.isEmpty()) {
            painted = paintClient(image, dir, bounds);
        }
        if (!painted) {
            paintWalkable(image, mapId, bounds);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private boolean paintClient(BufferedImage image, Path dir, Bounds bounds) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".s32") || name.endsWith(".seg")) files.add(path);
            }
        }
        if (files.isEmpty()) return false;
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        int hits = 0;
        for (Path file : files) {
            int[] parsed = parseBlockName(file.getFileName().toString());
            if (parsed == null) continue;
            int worldX = ORIGIN + (parsed[0] - 0x8000) * BLOCK;
            int worldY = ORIGIN + (parsed[1] - 0x8000) * BLOCK;
            if (worldX + BLOCK <= bounds.startX || worldX > bounds.endX
                    || worldY + BLOCK <= bounds.startY || worldY > bounds.endY) {
                continue;
            }
            int[] cells = loadBlock(file);
            if (cells == null) continue;
            for (int ly = 0; ly < BLOCK; ly++) {
                int gy = worldY + ly;
                if (gy < bounds.startY || gy > bounds.endY) continue;
                int py = gy - bounds.startY;
                if (py < 0 || py >= image.getHeight()) continue;
                for (int lx = 0; lx < BLOCK; lx++) {
                    int gx = worldX + lx;
                    if (gx < bounds.startX || gx > bounds.endX) continue;
                    int px = gx - bounds.startX;
                    if (px < 0 || px >= image.getWidth()) continue;
                    int color = colorOf(cells[(ly * BLOCK + lx) * 3],
                            cells[(ly * BLOCK + lx) * 3 + 1],
                            cells[(ly * BLOCK + lx) * 3 + 2]);
                    if (color != 0) {
                        image.setRGB(px, py, color);
                        hits++;
                    }
                }
            }
        }
        return hits > 32;
    }

    private int colorOf(int a, int b, int c) {
        int color = tileColor(a);
        if (color != 0) return color;
        color = tileColor(b);
        if (color != 0) return color;
        return tileColor(c);
    }

    private int tileColor(int raw) {
        if (raw == 0) return 0;
        int id = raw & 0x00FFFFFF;
        int tileset = id & 0x0FFF;
        int index = (id >>> 12) & 0xFF;
        int[] colors = tilColors.get(tileset);
        if (colors == null) {
            colors = decodeTilColors(tileset);
            tilColors.put(tileset, colors);
        }
        if (index < 0 || index >= colors.length) return 0;
        return colors[index];
    }

    private int[] decodeTilColors(int tileset) {
        TilFile file = tils.get(tileset);
        int[] colors = new int[256];
        if (file == null) return colors;
        try {
            byte[] data = extract(file);
            if (data.length < 12) return colors;
            int count = Math.min(256, i32(data, 0));
            if (count <= 0) return colors;
            int table = 4;
            int dataStart = 4 + count * 4;
            if (dataStart >= data.length) return colors;
            for (int i = 0; i < count; i++) {
                int rel = i32(data, table + i * 4);
                int start = dataStart + rel;
                int end = i + 1 < count ? dataStart + i32(data, table + (i + 1) * 4) : data.length;
                if (start < 0 || start >= data.length || end <= start) continue;
                end = Math.min(end, data.length);
                colors[i] = averageDecodedTile(data, start, end);
            }
        } catch (IOException ignored) {
        }
        return colors;
    }

    private static int averageDecodedTile(byte[] data, int start, int end) {
        if (end - start < 4) return 0;
        byte[] tile = new byte[end - start];
        System.arraycopy(data, start, tile, 0, tile.length);
        long r = 0, g = 0, b = 0;
        int n = 0;
        int p = 0;
        int x = 0;
        int y = 0;
        int w = 24;
        int h = 24;
        while (p < tile.length && y < h) {
            int cmd = tile[p++] & 255;
            if ((cmd & 0x80) != 0) {
                x += cmd & 0x7F;
            } else {
                if (p + cmd * 2 > tile.length) break;
                for (int i = 0; i < cmd; i++) {
                    int v = (tile[p] & 255) | ((tile[p + 1] & 255) << 8);
                    p += 2;
                    int argb = SpritePreview.rgb555(v);
                    if ((argb >>> 24) > 8) {
                        r += (argb >> 16) & 255;
                        g += (argb >> 8) & 255;
                        b += argb & 255;
                        n++;
                    }
                    x++;
                    if (x >= w) {
                        x = 0;
                        y++;
                        if (y >= h) break;
                    }
                }
            }
            while (x >= w) {
                x -= w;
                y++;
            }
        }
        if (n < 8) return 0;
        return ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    private void paintWalkable(BufferedImage image, int mapId, Bounds bounds) throws IOException {
        Path file = serverMaps.resolve(mapId + ".txt");
        int hue = (mapId * 47) & 255;
        int walk = 0x3d6a80;
        int blocked = 0x121417;
        if (!Files.isRegularFile(file)) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, blocked);
                }
            }
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        int rows = Math.min(image.getHeight(), lines.size());
        for (int y = 0; y < rows; y++) {
            String line = lines.get(y);
            int col = 0;
            int start = 0;
            for (int i = 0; i <= line.length() && col < image.getWidth(); i++) {
                if (i == line.length() || line.charAt(i) == ',') {
                    if (i > start) {
                        int tile = 0;
                        boolean any = false;
                        for (int k = start; k < i; k++) {
                            char c = line.charAt(k);
                            if (c >= '0' && c <= '9') {
                                any = true;
                                tile = tile * 10 + (c - '0');
                            }
                        }
                        if (any && tile != 0) {
                            int shade = 90 + (tile % 40);
                            image.setRGB(col, y, rgbMix(walk, hue, shade));
                        } else {
                            image.setRGB(col, y, blocked);
                        }
                    }
                    col++;
                    start = i + 1;
                }
            }
        }
    }

    private static int rgbMix(int base, int hue, int shade) {
        int r = Math.min(255, ((base >> 16) & 255) + (hue / 8));
        int g = Math.min(255, ((base >> 8) & 255) + (shade / 6));
        int b = Math.min(255, (base & 255));
        return (r << 16) | (g << 8) | b;
    }

    private Bounds resolveBounds(int mapId, int startX, int startY, int endX, int endY) {
        Path dir = clientRoot.resolve("map").resolve(Integer.toString(mapId));
        Bounds fromFiles = boundsFromFiles(dir);
        boolean dbOk = endX > startX && endY > startY && (endX - startX) < 8000 && (endY - startY) < 8000;
        if (dbOk && fromFiles != null) {
            return new Bounds(
                    Math.max(startX, fromFiles.startX),
                    Math.max(startY, fromFiles.startY),
                    Math.min(endX, fromFiles.endX),
                    Math.min(endY, fromFiles.endY),
                    "client");
        }
        if (fromFiles != null) return fromFiles;
        if (dbOk) return new Bounds(startX, startY, endX, endY, "mapids");
        return new Bounds(32768, 32768, 32831, 32831, "fallback");
    }

    private static Bounds boundsFromFiles(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                int[] parsed = parseBlockName(path.getFileName().toString());
                if (parsed == null) continue;
                int wx = ORIGIN + (parsed[0] - 0x8000) * BLOCK;
                int wy = ORIGIN + (parsed[1] - 0x8000) * BLOCK;
                if (wx < minX) minX = wx;
                if (wy < minY) minY = wy;
                if (wx + BLOCK - 1 > maxX) maxX = wx + BLOCK - 1;
                if (wy + BLOCK - 1 > maxY) maxY = wy + BLOCK - 1;
            }
        } catch (IOException e) {
            return null;
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new Bounds(minX, minY, maxX, maxY, "client");
    }

    private static int[] parseBlockName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot != 8) return null;
        String hex = name.substring(0, 8);
        try {
            return new int[] {Integer.parseInt(hex.substring(0, 4), 16), Integer.parseInt(hex.substring(4, 8), 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int[] loadBlock(Path file) throws IOException {
        String key = file.toAbsolutePath().toString().toLowerCase(Locale.ROOT);
        int[] cached = blockCache.get(key);
        if (cached != null) return cached;
        byte[] raw = Files.readAllBytes(file);
        int[] cells = new int[CELLS * 3];
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".s32") && raw.length >= S32_BYTES) {
            for (int i = 0; i < CELLS; i++) {
                int o = i * S32_CELL;
                cells[i * 3] = i32(raw, o);
                cells[i * 3 + 1] = i32(raw, o + 4);
                cells[i * 3 + 2] = i32(raw, o + 8);
            }
        } else if (name.endsWith(".seg") && raw.length >= CELLS * SEG_CELL) {
            for (int i = 0; i < CELLS; i++) {
                int o = i * SEG_CELL;
                cells[i * 3] = u16(raw, o);
                cells[i * 3 + 1] = u16(raw, o + 2);
                cells[i * 3 + 2] = u16(raw, o + 4);
            }
        } else {
            return null;
        }
        if (blockCache.size() > 400) blockCache.clear();
        blockCache.put(key, cells);
        return cells;
    }

    private void indexTiles(Path idx) throws IOException {
        Path pak = idx.resolveSibling("Tile.Pak");
        if (!Files.isRegularFile(pak)) pak = idx.resolveSibling("Tile.pak");
        if (!Files.isRegularFile(pak)) return;
        byte[] raw = Files.readAllBytes(idx);
        int declared = i32(raw, 0);
        byte[] body = slice(raw, 4);
        if (!indexTileBody(pak, body, declared)) {
            indexTileBody(pak, PakCodec.decode(raw, 4), declared);
        }
    }

    private boolean indexTileBody(Path pak, byte[] body, int declared) {
        int records = Math.min(declared, body.length / 28);
        int hits = 0;
        for (int i = 0; i < records; i++) {
            int s = i * 28;
            int offset = i32(body, s);
            String name = zname(body, s + 4, 20);
            int size = i32(body, s + 24);
            int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".til");
            if (dot <= 0) continue;
            try {
                int id = Integer.parseInt(name.substring(0, dot));
                tils.put(id, new TilFile(pak, offset, size));
                hits++;
            } catch (NumberFormatException ignored) {
            }
        }
        return hits > 0;
    }

    private static byte[] extract(TilFile file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.pak.toFile(), "r")) {
            if (file.offset < 0 || file.size <= 0 || file.offset + (long) file.size > raf.length()) {
                return new byte[0];
            }
            byte[] data = new byte[file.size];
            raf.seek(file.offset);
            raf.readFully(data);
            return data;
        }
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

    private static final class TilFile {
        final Path pak;
        final int offset;
        final int size;

        TilFile(Path pak, int offset, int size) {
            this.pak = pak;
            this.offset = offset;
            this.size = size;
        }
    }

    private static final class Bounds {
        final int startX;
        final int startY;
        final int endX;
        final int endY;
        final String source;

        Bounds(int startX, int startY, int endX, int endY, String source) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.source = source;
        }

        int width() {
            return Math.max(1, endX - startX + 1);
        }

        int height() {
            return Math.max(1, endY - startY + 1);
        }
    }
}
