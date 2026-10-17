package local.longtan.gmtool;

import javax.imageio.ImageIO;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

final class GameBridge {
    private final ReentrantLock sendLock = new ReentrantLock();
    private volatile long windowCacheUntil;
    private volatile boolean windowCache;
    private volatile String detectedName = "";
    private final Path focusScript;
    private final Path captureScript;
    private final Path capturePng;

    GameBridge() {
        Path home = Path.of(System.getProperty("gmtool.home", "")).toAbsolutePath().normalize();
        if (home.toString().isBlank() || home.getFileName() == null) {
            home = Path.of("").toAbsolutePath();
        }
        Path data = home.resolve("data");
        this.focusScript = data.resolve("focus-game.ps1");
        this.captureScript = data.resolve("capture-game.ps1");
        this.capturePng = data.resolve("game-window.png");
        try {
            Files.createDirectories(data);
            copyScript("/ps/focus-game.ps1", focusScript);
            copyScript("/ps/capture-game.ps1", captureScript);
        } catch (IOException ignored) {
        }
    }

    private static void copyScript(String resource, Path dest) throws IOException {
        try (InputStream in = GameBridge.class.getResourceAsStream(resource)) {
            if (in != null) Files.write(dest, in.readAllBytes());
        }
    }

    String detectedName() {
        isGameWindowAvailable();
        return detectedName;
    }

    boolean isGameWindowAvailable() {
        long now = System.currentTimeMillis();
        if (now < windowCacheUntil) {
            return windowCache;
        }
        boolean found = hasGameProcess() || findGameWindow();
        windowCache = found;
        windowCacheUntil = now + 1500;
        if (!found) detectedName = "";
        return found;
    }

    Map<String, Object> captureWindow() throws IOException, InterruptedException {
        if (!isGameWindowAvailable()) {
            throw new IllegalStateException("未找到游戏窗口 TW13032701.bin / Lin.bin");
        }
        if (!Files.isRegularFile(captureScript)) {
            throw new IllegalStateException("缺少截取脚本 capture-game.ps1");
        }
        sendLock.lock();
        try {
            Files.deleteIfExists(capturePng);
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-STA", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", captureScript.toAbsolutePath().toString(),
                    "-OutFile", capturePng.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] out = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            String text = new String(out, StandardCharsets.UTF_8).trim();
            if (code != 0 || !Files.isRegularFile(capturePng)) {
                throw new IllegalStateException(captureError(code, text));
            }
            byte[] png = Files.readAllBytes(capturePng);
            if (png.length < 20000) {
                throw new IllegalStateException("截到的图过小，多半是黑屏（PrintWindow 0 那种），不是游戏画面");
            }
            BufferedImage image = ImageIO.read(capturePng.toFile());
            if (image == null) {
                throw new IllegalStateException("截图不是有效 PNG");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("png", java.util.Base64.getEncoder().encodeToString(png));
            result.put("width", image.getWidth());
            result.put("height", image.getHeight());
            result.put("bytes", png.length);
            result.put("detail", text);
            result.put("window", detectedName);
            return result;
        } finally {
            sendLock.unlock();
        }
    }

    private static String captureError(int code, String text) {
        if (text.contains("NO_WINDOW") || code == 2) return "未找到游戏窗口";
        if (text.contains("MINIMIZED") || code == 4) return "游戏窗口最小化了，先还原再截";
        if (text.contains("PRINTWINDOW_FAIL")) return "PrintWindow 失败";
        if (text.isBlank()) return "截取失败，退出码 " + code;
        return text;
    }

    void sendCommand(String command) throws IOException, InterruptedException {
        if (command == null || command.isBlank() || command.length() > 240) {
            throw new IllegalArgumentException("指令为空或过长");
        }
        if (!isGameWindowAvailable()) {
            throw new IllegalStateException("未找到游戏窗口 TW13032701.bin / Lin.bin");
        }
        sendLock.lock();
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(command), null);
            if (!pasteIntoGame()) {
                throw new IllegalStateException("无法把游戏窗口切到前台。指令已在剪贴板：" + command
                        + "。请点一下游戏画面，Ctrl+V 后回车");
            }
            windowCacheUntil = 0;
        } finally {
            sendLock.unlock();
        }
    }

    private boolean hasGameProcess() {
        return ProcessHandle.allProcesses().anyMatch(process -> process.info().command()
                .map(command -> {
                    if (!isGameExe(command)) return false;
                    String lower = command.replace('/', '\\');
                    int slash = lower.lastIndexOf('\\');
                    detectedName = slash >= 0 ? lower.substring(slash + 1) : command;
                    return true;
                })
                .orElse(false));
    }

    static boolean isGameExe(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase(Locale.ROOT).replace('/', '\\');
        int slash = lower.lastIndexOf('\\');
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        return name.equals("lin.bin")
                || name.equals("lin.exe")
                || name.equals("tw13032701.bin")
                || name.endsWith("13032701.bin");
    }

    private boolean findGameWindow() {
        boolean found = runPowerShell(
                "$p = Get-Process | Where-Object {"
                        + " $_.ProcessName -match '^(Lin|TW13032701)'"
                        + " -or $_.MainWindowTitle -match '13032701'"
                        + " -or ($_.Path -and ($_.Path -match 'Lin\\.bin$' -or $_.Path -match '13032701\\.bin$'))"
                        + "} | Where-Object { [int64]$_.MainWindowHandle -ne 0 } | Select-Object -First 1;"
                        + " if ($p) { exit 0 } else { exit 1 }",
                false);
        if (found && (detectedName == null || detectedName.isBlank())) {
            detectedName = "TW13032701.bin";
        }
        return found;
    }

    private boolean pasteIntoGame() {
        if (Files.isRegularFile(focusScript)) {
            return runFile(focusScript);
        }
        return runPowerShell(
                "$p = Get-Process | Where-Object { $_.MainWindowTitle -match '13032701' } | Select-Object -First 1;"
                        + " if (-not $p) { exit 1 };"
                        + " $s = New-Object -ComObject WScript.Shell;"
                        + " if (-not $s.AppActivate($p.Id)) { exit 1 };"
                        + " Start-Sleep -Milliseconds 220;"
                        + " $s.SendKeys('~'); Start-Sleep -Milliseconds 120;"
                        + " $s.SendKeys('^v'); Start-Sleep -Milliseconds 100;"
                        + " $s.SendKeys('~'); exit 0",
                true);
    }

    private boolean runFile(Path script) {
        try {
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-STA", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] out = process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean runPowerShell(String script, boolean sta) {
        try {
            ProcessBuilder builder = sta
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-NonInteractive", "-Command", script)
                    : new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
            Process process = builder.redirectErrorStream(true).start();
            byte[] ignored = process.getInputStream().readAllBytes();
            return process.waitFor() == 0 && ignored != null;
        } catch (Exception e) {
            return false;
        }
    }
}
