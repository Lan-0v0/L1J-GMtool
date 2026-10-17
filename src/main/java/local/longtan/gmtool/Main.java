package local.longtan.gmtool;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path home = resolveHome();
        Path data = home.resolve("data");
        Files.createDirectories(data);

        FileChannel lockChannel = FileChannel.open(data.resolve("gmtool.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            openExisting(data);
            return;
        }

        AuditLog audit = new AuditLog(data.resolve("audit.log"));
        Database database = new Database(home, audit);
        GameBridge gameBridge = new GameBridge();
        ServerBridge serverBridge = new ServerBridge(home);
        PendingStore pending = new PendingStore(data.resolve("pending.json"), database, audit);
        MapTiles mapTiles = new MapTiles(home);
        SpritePreview sprites = new SpritePreview(home);
        AppServer server = new AppServer(InetAddress.getLoopbackAddress(),
                database, gameBridge, serverBridge, audit, pending, mapTiles, sprites);
        pending.start();
        int port = server.start();
        Files.writeString(data.resolve("gmtool.port"), Integer.toString(port), StandardCharsets.US_ASCII);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pending.stop();
            server.stop();
            try {
                lock.release();
                lockChannel.close();
                Files.deleteIfExists(data.resolve("gmtool.port"));
            } catch (IOException ignored) {
            }
        }, "gmtool-shutdown"));

        try {
            database.writeGmAccess("gm", "小韦", 200);
            try {
                Map<String, Object> live = serverBridge.setAccess("小韦", 200);
                audit.write("权限内存", String.valueOf(live));
            } catch (Exception e) {
                audit.write("权限内存", "稍后上线再生效: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("写入小韦 GM 权限失败: " + e.getMessage());
        }

        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("龙潭 GM 台: " + url);
        if (!hasArg(args, "--no-open") && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(url));
        }
    }

    private static Path resolveHome() {
        String configured = System.getProperty("gmtool.home", "").trim();
        Path home = configured.isEmpty() ? Paths.get("").toAbsolutePath() : Paths.get(configured).toAbsolutePath();
        return home.normalize();
    }

    private static boolean hasArg(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void openExisting(Path data) throws Exception {
        Path portFile = data.resolve("gmtool.port");
        if (!Files.exists(portFile)) {
            throw new IllegalStateException("GM 台已运行，但端口信息不可用。");
        }
        String port = Files.readString(portFile, StandardCharsets.US_ASCII).trim();
        Desktop.getDesktop().browse(URI.create("http://127.0.0.1:" + port + "/"));
    }
}
