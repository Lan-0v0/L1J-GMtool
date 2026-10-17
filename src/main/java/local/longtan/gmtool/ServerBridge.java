package local.longtan.gmtool;

import com.sun.tools.attach.VirtualMachine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ServerBridge {
    static final int AGENT_PORT = 17980;
    private static final int NEED_VER = 3;
    private final Path agentJar;
    private final Path findScript;
    private volatile boolean attached;
    // 远端模式：服务端跑在云端，本机没有 com.lineage.Server 进程可注入。
    // 此模式下所有「注入式」指令改为明确报错，GM 指令请走游戏窗口通道。
    private final boolean remote;
    // 只认 17980。曾经用过 17981/17982 旁路助手，实测 AgentLoadException 且破坏待办记录，已废弃。
    private final int agentPort = AGENT_PORT;

    ServerBridge(Path home, boolean remote) throws Exception {
        this.remote = remote;
        Path built = home.resolve("GMAgent.jar");
        Path copied = copyAgent(built);
        this.agentJar = copied != null ? copied : built;
        Path data = home.resolve("data");
        Files.createDirectories(data);
        this.findScript = data.resolve("find-server.ps1");
        try (InputStream in = ServerBridge.class.getResourceAsStream("/ps/find-server.ps1")) {
            if (in != null) Files.write(findScript, in.readAllBytes());
        }
    }

    private static Path copyAgent(Path built) {
        if (!Files.isRegularFile(built)) return null;
        Path unique = Path.of(System.getProperty("java.io.tmpdir"),
                "longtan-gm-agent-" + System.currentTimeMillis() + ".jar");
        try {
            Files.copy(built, unique, StandardCopyOption.REPLACE_EXISTING);
            return unique;
        } catch (Exception e) {
            return built;
        }
    }

    Map<String, Object> status() {
        Map<String, Object> row = new LinkedHashMap<>();
        if (remote) {
            row.put("agent", false);
            row.put("agentVer", 0);
            row.put("agentPort", 0);
            row.put("serverPids", new ArrayList<Long>());
            row.put("agentMode", "remote");
            row.put("agentError", "远端模式：服务端在云端，指令走游戏窗口");
            return row;
        }
        int ver = pingVer();
        boolean up = ver >= NEED_VER;
        row.put("agent", up);
        row.put("agentVer", ver);
        row.put("agentPort", agentPort);
        row.put("serverPids", findServerPids());
        if (!up) {
            try {
                ensure();
                ver = pingVer();
                row.put("agent", ver >= NEED_VER);
                row.put("agentVer", ver);
                row.put("agentPort", agentPort);
            } catch (Exception e) {
                row.put("agentError", e.getMessage());
            }
        }
        return row;
    }

    synchronized void ensure() {
        if (remote) {
            throw new IllegalStateException("远端模式：服务端在云端，无法注入。GM 指令请通过游戏窗口发送（.给予o / .传送o 等）。");
        }
        if (pingVer() >= NEED_VER) {
            attached = true;
            return;
        }
        List<Long> pids = findServerPids();
        if (pids.isEmpty()) {
            throw new IllegalStateException("找不到正在运行的游戏服（本机 java 进程里没有 com.lineage.Server）。GMtool 必须和 Server_Game 在同一台机器上才能注入。");
        }
        if (!Files.isRegularFile(agentJar)) {
            throw new IllegalStateException("找不到 GMAgent.jar：" + agentJar);
        }
        int before = pingVer();
        long pid = pids.get(0);
        // 固定 17980：不再把新助手塞到 17981/17982 旁路。
        String listen = Integer.toString(AGENT_PORT);
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
            vm.loadAgent(agentJar.toAbsolutePath().toString(), listen);
        } catch (Exception e) {
            if (pingVer() < NEED_VER) {
                throw new IllegalStateException(staleAgentMessage(pids, before, e.getMessage()));
            }
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (Exception ignored) {
                }
            }
        }
        for (int i = 0; i < 20; i++) {
            if (pingVer() >= NEED_VER) {
                attached = true;
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException(staleAgentMessage(pids, pingVer(), "attach 后仍不是 ver=" + NEED_VER));
    }

    private static String staleAgentMessage(List<Long> pids, int ver, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("GMAgent 不是新版(ver=").append(ver).append("，需要 ").append(NEED_VER).append(")。");
        sb.append("游戏服 PID=").append(pids).append("。");
        sb.append("请只留一个 Server_Game，先关掉占着 17980 的那个进程，再开 GM 台。");
        if (detail != null && !detail.isEmpty()) sb.append(" 详情：").append(detail);
        return sb.toString();
    }

    Map<String, Object> command(String operator, String cmd) {
        ensure();
        if (cmd.startsWith(".")) cmd = cmd.substring(1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "command");
        if (operator != null && !operator.isBlank()) body.put("operator", operator);
        body.put("cmd", cmd);
        return post(body);
    }

    Map<String, Object> setAccess(String name, int level) {
        ensure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "access");
        body.put("name", name);
        body.put("level", level);
        return post(body);
    }

    Map<String, Object> spawnLive(String operator, int npcId) {
        return spawnLive(operator, npcId, 0, 0, 0, 6);
    }

    Map<String, Object> spawnLive(String operator, int npcId, int x, int y, int map, int heading) {
        ensure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "spawn-live");
        if (operator != null && !operator.isBlank()) body.put("operator", operator);
        body.put("npcId", npcId);
        if (x > 0) body.put("x", x);
        if (y > 0) body.put("y", y);
        if (map > 0) body.put("map", map);
        body.put("heading", heading);
        return post(body);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> liveLocs() {
        ensure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "live-locs");
        Map<String, Object> parsed = post(body);
        Object players = parsed.get("players");
        List<Map<String, Object>> out = new ArrayList<>();
        if (players instanceof List) {
            for (Object row : (List<?>) players) {
                if (row instanceof Map) out.add((Map<String, Object>) row);
            }
        }
        return out;
    }

    Map<String, Object> despawn(int objId, int npcId) {
        ensure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "despawn");
        body.put("objId", objId);
        body.put("npcId", npcId);
        return post(body);
    }

    Map<String, Object> despawnSpawn(int spawnId, int npcId) {
        ensure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "despawn-spawn");
        body.put("spawnId", spawnId);
        body.put("npcId", npcId);
        return post(body);
    }

    Map<String, Object> despawnNpc(int npcId, int map, int x, int y, int radius) {
        ensure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "despawn-npc");
        body.put("npcId", npcId);
        body.put("map", map);
        if (x > 0) body.put("x", x);
        if (y > 0) body.put("y", y);
        if (radius > 0) body.put("radius", radius);
        return post(body);
    }

    private Map<String, Object> post(Map<String, Object> body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + agentPort + "/invoke")
                    .toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            String text = read(conn);
            Map<String, Object> parsed = Json.parseObject(text);
            if (!Boolean.TRUE.equals(parsed.get("ok"))) {
                throw new IllegalStateException(String.valueOf(parsed.getOrDefault("error", "agent 调用失败")));
            }
            return parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用游戏服指令链失败：" + e.getMessage());
        }
    }

    private int pingVer() {
        return pingVerAt(AGENT_PORT);
    }

    private int pingVerAt(int port) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/ping")
                    .toURL().openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(600);
            conn.setRequestMethod("GET");
            String text = read(conn);
            if (!text.contains("\"ok\":true") || !text.contains("longtan")) return 0;
            // 通用解析 "ver":N（同时兼容 "ver": N）——不再写死版本号。
            // 旧实现只 contains("ver":2)，agent 升到 ver:3 之后会被 fallthrough 判成 1，
            // 于是 ver(1) < NEED_VER(3)，整台 GM 工具全部功能判定"GMAgent 不是新版"而不可用。
            int at = text.indexOf("\"ver\"");
            if (at < 0) return 1;
            int colon = text.indexOf(':', at);
            if (colon < 0) return 1;
            int i = colon + 1;
            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i++;
            }
            int start = i;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (i == start) return 1;
            try {
                return Integer.parseInt(text.substring(start, i));
            } catch (NumberFormatException e) {
                return 1;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private long findServerPid() {
        List<Long> pids = findServerPids();
        return pids.isEmpty() ? -1 : pids.get(0);
    }

    private List<Long> findServerPids() {
        List<Long> pids = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(process -> {
                String command = process.info().command().orElse("").toLowerCase(Locale.ROOT);
                if (!command.contains("java")) return;
                String args = process.info().arguments().map(a -> String.join(" ", a)).orElse("").toLowerCase(Locale.ROOT);
                String line = (command + " " + args).toLowerCase(Locale.ROOT);
                if (isGameServerLine(line)) pids.add(process.pid());
            });
        } catch (Exception ignored) {
        }
        if (pids.isEmpty()) pids.addAll(findPidsByWmi());
        return pids;
    }

    private static boolean isGameServerLine(String line) {
        if (line == null || line.isEmpty()) return false;
        String n = line.toLowerCase(Locale.ROOT);
        if (n.contains("gmtool") || n.contains("l1jserver.jar")) return false;
        return n.contains("com.lineage.server") || n.contains("server_game.jar");
    }

    private List<Long> findPidsByWmi() {
        List<Long> pids = new ArrayList<>();
        String ps = "Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java' -and $_.CommandLine -and ($_.CommandLine -match 'com\\.lineage\\.Server' -or $_.CommandLine -match 'Server_Game\\.jar') -and $_.CommandLine -notmatch 'GMTool' } | ForEach-Object { $_.ProcessId }";
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(6, TimeUnit.SECONDS);
            for (String line : text.split("\\R")) {
                String t = line.trim();
                if (t.matches("\\d+")) pids.add(Long.parseLong(t));
            }
        } catch (Exception ignored) {
        }
        if (pids.isEmpty()) {
            long fallback = findPidByPowerShell();
            if (fallback > 0) pids.add(fallback);
        }
        return pids;
    }

    private long findPidByPowerShell() {
        try {
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", findScript.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(4, TimeUnit.SECONDS);
            if (text.isEmpty()) return -1;
            String first = text.split("\\R")[0].trim();
            return Long.parseLong(first);
        } catch (Exception e) {
            return -1;
        }
    }
}
