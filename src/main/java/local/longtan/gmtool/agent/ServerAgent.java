package local.longtan.gmtool.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Loaded into the live Server_Game.jar JVM. Does not modify the jar on disk.
 * Mirrors C_Chat: GMCommands.handleCommands(pc, cmdWithoutDot).
 */
public final class ServerAgent {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile Instrumentation inst;
    private static volatile HttpServer http;
    private static volatile int boundPort = 17980;

    private ServerAgent() {
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        int port = 17980;
        try {
            if (args != null && args.trim().length() > 0) {
                port = Integer.parseInt(args.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        start(port);
    }

    public static void premain(String args, Instrumentation instrumentation) {
        agentmain(args, instrumentation);
    }

    private static synchronized void start(int port) {
        if (http != null) return;
        // 只绑指定端口（默认 17980）。不再回退 17981/17982 旁路助手：
        // 那条路实测 AgentLoadException，且会让 GMtool 连不到真正的助手。
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/ping", ServerAgent::ping);
            server.createContext("/invoke", ServerAgent::invoke);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "longtan-gm-agent");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            http = server;
            boundPort = port;
        } catch (Exception e) {
            throw new IllegalStateException("GM agent listen failed on " + port + ": " + e.getMessage(), e);
        }
    }

    private static void ping(HttpExchange exchange) {
        reply(exchange, 200, "{\"ok\":true,\"agent\":\"longtan\",\"ver\":3}");
    }

    private static void invoke(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                reply(exchange, 400, "{\"ok\":false,\"error\":\"POST only\"}");
                return;
            }
            String body = read(exchange);
            Map<String, String> req = parse(body);
            String op = str(req, "op");
            String result;
            if ("command".equals(op)) {
                result = runCommand(str(req, "operator"), str(req, "cmd"));
            } else if ("access".equals(op)) {
                result = setAccess(str(req, "name"), toInt(str(req, "level"), 200));
              } else if ("spawn-live".equals(op)) {
                result = spawnLive(str(req, "operator"), toInt(str(req, "npcId"), 0),
                        toInt(str(req, "x"), 0), toInt(str(req, "y"), 0),
                        toInt(str(req, "map"), 0), toInt(str(req, "heading"), 6));
            } else if ("despawn".equals(op)) {
                result = despawn(toInt(str(req, "objId"), 0), toInt(str(req, "npcId"), 0));
            } else if ("despawn-spawn".equals(op)) {
                result = despawnSpawn(toInt(str(req, "spawnId"), 0), toInt(str(req, "npcId"), 0));
            } else if ("despawn-npc".equals(op)) {
                result = despawnNpc(toInt(str(req, "npcId"), 0), toInt(str(req, "map"), -1),
                        toInt(str(req, "x"), 0), toInt(str(req, "y"), 0), toInt(str(req, "radius"), 0));
            } else if ("live-locs".equals(op)) {
                result = liveLocs();
            } else {
                result = "{\"ok\":false,\"error\":\"unknown op\"}";
            }
            reply(exchange, 200, result);
        } catch (Exception e) {
            reply(exchange, 200, "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private static String runCommand(String operator, String cmd) throws Exception {
        if (cmd == null || cmd.length() == 0) {
            return "{\"ok\":false,\"error\":\"missing cmd\"}";
        }
        if (cmd.startsWith(".")) cmd = cmd.substring(1);
        Object pc = null;
        if (operator != null && operator.length() > 0) {
            pc = player(operator);
        }
        if (pc == null && isGiveCommand(cmd)) {
            String wh = giveWarehouse(cmd);
            if (wh != null) return wh;
        }
        try {
            return invokeExecutor(pc, cmd);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (pc == null) {
                try {
                    Class<?> gmCl = find("com.lineage.server.command.GMCommands");
                    Object gm = gmCl.getMethod("getInstance").invoke(null);
                    gmCl.getMethod("handleCommands", String.class).invoke(gm, cmd);
                    return "{\"ok\":true,\"cmd\":\"" + esc(cmd) + "\",\"via\":\"console\"}";
                } catch (Exception e2) {
                    return "{\"ok\":false,\"error\":\"该指令需要在线角色作为落点（不必是GM号）: "
                            + esc(c.getMessage()) + "\"}";
                }
            }
            return "{\"ok\":false,\"error\":\"" + esc(c.getMessage()) + "\"}";
        }
    }

    private static boolean isGiveCommand(String cmd) {
        String n = cmd.toLowerCase();
        return n.startsWith("给予o") || n.startsWith("给予");
    }

    private static String giveWarehouse(String cmd) throws Exception {
        String rest = cmd;
        int sp = cmd.indexOf(' ');
        if (sp < 0) return null;
        rest = cmd.substring(sp + 1).trim();
        String[] parts = rest.split("\\s+");
        if (parts.length < 2) return "{\"ok\":false,\"error\":\"给予o 帐号 物品编号 数量\"}";
        String accname = parts[0];
        int itemId = Integer.parseInt(parts[1]);
        int count = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
        Class<?> accRead = find("com.lineage.server.datatables.lock.AccountReading");
        Object accApi = accRead.getMethod("get").invoke(null);
        Object acc = accRead.getMethod("getAccount", String.class).invoke(accApi, accname.toLowerCase());
        if (acc == null) return "{\"ok\":false,\"error\":\"没有该帐号\"}";
        String login = String.valueOf(acc.getClass().getMethod("get_login").invoke(acc));
        Class<?> items = find("com.lineage.server.datatables.ItemTable");
        Object table = items.getMethod("get").invoke(null);
        Object item = items.getMethod("createItem", int.class).invoke(table, Integer.valueOf(itemId));
        if (item == null) return "{\"ok\":false,\"error\":\"没有该物品\"}";
        item.getClass().getMethod("setCount", long.class).invoke(item, Long.valueOf(count));
        Class<?> dwarf = find("com.lineage.server.datatables.lock.DwarfReading");
        Object dwarfApi = dwarf.getMethod("get").invoke(null);
        Class<?> itemCl = find("com.lineage.server.model.Instance.L1ItemInstance");
        dwarf.getMethod("insertItem", String.class, itemCl).invoke(dwarfApi, login, item);
        return "{\"ok\":true,\"cmd\":\"" + esc(cmd) + "\",\"via\":\"warehouse\"}";
    }

    private static String invokeExecutor(Object pc, String cmdLine) throws Exception {
        String cmd = cmdLine;
        String arg = "";
        int sp = cmdLine.indexOf(' ');
        if (sp >= 0) {
            cmd = cmdLine.substring(0, sp);
            arg = cmdLine.substring(sp + 1).trim();
        }
        Class<?> tableCl = find("com.lineage.server.datatables.CommandsTable");
        Object table = tableCl.getMethod("get").invoke(null);
        Object command = tableCl.getMethod("get", String.class).invoke(table, cmd.toLowerCase());
        if (command == null) {
            return "{\"ok\":false,\"error\":\"没有这个命令: " + esc(cmd) + "\"}";
        }
        String className = String.valueOf(command.getClass().getMethod("getExecutorClassName").invoke(command));
        if (!className.contains(".")) {
            className = "com.lineage.server.command.executor." + className;
        }
        Class<?> exeCl = find(className);
        Object exe = exeCl.getMethod("getInstance").invoke(null);
        Class<?> pcCl = find("com.lineage.server.model.Instance.L1PcInstance");
        exeCl.getMethod("execute", pcCl, String.class, String.class)
                .invoke(exe, pc, cmd.toLowerCase(), arg.toLowerCase());
        return "{\"ok\":true,\"cmd\":\"" + esc(cmdLine) + "\",\"via\":\"executor\"}";
    }

    private static String setAccess(String name, int level) throws Exception {
        Object pc = player(name);
        if (pc == null) {
            return "{\"ok\":false,\"error\":\"not in world: " + esc(name) + "\"}";
        }
        Class<?> pcCl = find("com.lineage.server.model.Instance.L1PcInstance");
        pcCl.getMethod("setAccessLevel", short.class).invoke(pc, Short.valueOf((short) level));
        try {
            pcCl.getMethod("setGm", boolean.class).invoke(pc, Boolean.valueOf(level > 0));
        } catch (NoSuchMethodException ignored) {
        }
        try {
            pcCl.getMethod("save").invoke(pc);
        } catch (Exception ignored) {
        }
        return "{\"ok\":true,\"name\":\"" + esc(name) + "\",\"level\":" + level + "}";
    }

    private static String spawnLive(String operator, int npcId, int atX, int atY, int atMap, int atHeading) throws Exception {
        if (npcId <= 0) {
            return "{\"ok\":false,\"error\":\"missing npcId\"}";
        }
        Object pc = null;
        if (operator != null && operator.length() > 0) {
            pc = player(operator);
        }
        int x = atX;
        int y = atY;
        int map = atMap;
        int heading = atHeading;
        if (pc != null) {
            if (x <= 0) x = intOf(pc.getClass().getMethod("getX").invoke(pc), 0);
            if (y <= 0) y = intOf(pc.getClass().getMethod("getY").invoke(pc), 0);
            if (map <= 0) map = intOf(pc.getClass().getMethod("getMapId").invoke(pc), 0);
            if (heading < 0 || heading > 7) heading = intOf(invokeOpt(pc, "getHeading"), 5);
        }
        if (heading < 0 || heading > 7) heading = 5;
        if (x <= 0 || y <= 0 || map < 0) {
            return "{\"ok\":false,\"error\":\"需要地图坐标，或一个在线角色作为落点\"}";
        }
        // 必须走 5 参数 spawn：它内部 npc.setHeading(传入值)。
        // 不能用 spawnRx —— 反编译 Server_Game.jar 的 L1SpawnUtil.spawnRx 第 196-197 字节码是
        // iconst_5 + setHeading，即写死朝向 5，会把 GMtool 算好的朝向吃掉。
        Class<?> util = find("com.lineage.server.utils.L1SpawnUtil");
        Method m = util.getMethod("spawn", int.class, int.class, int.class, short.class, int.class);
        Object npc = m.invoke(null, Integer.valueOf(npcId), Integer.valueOf(x), Integer.valueOf(y),
                Short.valueOf((short) map), Integer.valueOf(heading));
        if (npc == null) {
            return "{\"ok\":false,\"error\":\"找不到该npc\"}";
        }
        int objId = intOf(npc.getClass().getMethod("getId").invoke(npc), 0);
        int got = intOf(invokeOpt(npc, "getNpcId"), npcId);
        int gotHeading = intOf(invokeOpt(npc, "getHeading"), heading);
        x = intOf(invokeOpt(npc, "getX"), x);
        y = intOf(invokeOpt(npc, "getY"), y);
        map = intOf(invokeOpt(npc, "getMapId"), map);
        return "{\"ok\":true,\"objId\":" + objId + ",\"npcId\":" + got
                + ",\"x\":" + x + ",\"y\":" + y + ",\"map\":" + map
                + ",\"heading\":" + gotHeading + ",\"wantHeading\":" + heading + "}";
    }

    private static String despawn(int objId, int npcId) throws Exception {
        if (objId <= 0) {
            return "{\"ok\":false,\"error\":\"missing objId\"}";
        }
        Object world = world();
        Object obj = world.getClass().getMethod("findObject", int.class).invoke(world, Integer.valueOf(objId));
        if (obj == null) {
            return "{\"ok\":false,\"error\":\"场上已没有这只\"}";
        }
        Class<?> npcCl = find("com.lineage.server.model.Instance.L1NpcInstance");
        if (!npcCl.isInstance(obj)) {
            return "{\"ok\":false,\"error\":\"对象不是NPC\"}";
        }
        int got = intOf(invokeOpt(obj, "getNpcId"), 0);
        if (npcId > 0 && got != npcId) {
            return "{\"ok\":false,\"error\":\"不是当初那只NPC\"}";
        }
        npcCl.getMethod("deleteMe").invoke(obj);
        return "{\"ok\":true,\"objId\":" + objId + ",\"npcId\":" + got + "}";
    }

    private static String despawnSpawn(int spawnId, int npcId) throws Exception {
        if (spawnId <= 0) {
            return "{\"ok\":false,\"error\":\"missing spawnId\"}";
        }
        Class<?> npcCl = find("com.lineage.server.model.Instance.L1NpcInstance");
        java.util.Collection<?> all = npcAll();
        int removed = 0;
        for (Object obj : all) {
            if (obj == null || !npcCl.isInstance(obj)) continue;
            int gotNpc = intOf(invokeOpt(obj, "getNpcId"), 0);
            if (npcId > 0 && gotNpc != npcId) continue;
            Object spawn = invokeOpt(obj, "getSpawn");
            if (spawn == null) spawn = invokeOpt(obj, "get_spawn");
            if (spawn == null) continue;
            int sid = intOf(invokeOpt(spawn, "getId"), 0);
            if (sid != spawnId) continue;
            npcCl.getMethod("deleteMe").invoke(obj);
            removed++;
        }
        return "{\"ok\":true,\"removed\":" + removed + ",\"spawnId\":" + spawnId + "}";
    }

    private static String despawnNpc(int npcId, int map, int atX, int atY, int radius) throws Exception {
        if (npcId <= 0) {
            return "{\"ok\":false,\"error\":\"missing npcId\"}";
        }
        Class<?> npcCl = find("com.lineage.server.model.Instance.L1NpcInstance");
        java.util.Collection<?> all = npcAll();
        int removed = 0;
        for (Object obj : all) {
            if (obj == null || !npcCl.isInstance(obj)) continue;
            int gotNpc = intOf(invokeOpt(obj, "getNpcId"), 0);
            if (gotNpc != npcId) continue;
            int gotMap = intOf(invokeOpt(obj, "getMapId"), -1);
            if (map >= 0 && gotMap != map) continue;
            if (radius > 0 && atX > 0 && atY > 0) {
                int dx = intOf(invokeOpt(obj, "getX"), 0) - atX;
                int dy = intOf(invokeOpt(obj, "getY"), 0) - atY;
                if (dx * dx + dy * dy > radius * radius) continue;
            }
            npcCl.getMethod("deleteMe").invoke(obj);
            removed++;
        }
        return "{\"ok\":true,\"removed\":" + removed + ",\"npcId\":" + npcId + "}";
    }

    private static Object world() throws Exception {
        Class<?> worldCl = find("com.lineage.server.world.World");
        return worldCl.getMethod("get").invoke(null);
    }

    @SuppressWarnings("unchecked")
    private static String liveLocs() throws Exception {
        Object world = world();
        Object all = world.getClass().getMethod("getAllPlayers").invoke(world);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"players\":[");
        boolean first = true;
        if (all instanceof java.util.Collection) {
            for (Object pc : (java.util.Collection<?>) all) {
                if (pc == null) continue;
                if (!first) sb.append(',');
                first = false;
                String name = String.valueOf(invokeOpt(pc, "getName"));
                int id = intOf(invokeOpt(pc, "getId"), 0);
                int x = intOf(invokeOpt(pc, "getX"), 0);
                int y = intOf(invokeOpt(pc, "getY"), 0);
                int map = intOf(invokeOpt(pc, "getMapId"), 0);
                int heading = intOf(invokeOpt(pc, "getHeading"), 0);
                sb.append("{\"name\":\"").append(esc(name)).append("\",\"id\":").append(id)
                        .append(",\"x\":").append(x).append(",\"y\":").append(y)
                        .append(",\"map\":").append(map).append(",\"heading\":").append(heading)
                        .append("}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<?> npcAll() throws Exception {
        try {
            Class<?> wn = find("com.lineage.server.world.WorldNpc");
            Object inst = wn.getMethod("get").invoke(null);
            Object all = wn.getMethod("all").invoke(inst);
            if (all instanceof java.util.Collection) return (java.util.Collection<?>) all;
        } catch (Exception ignored) {
        }
        Object world = world();
        try {
            Object all = world.getClass().getMethod("getAllObjects").invoke(world);
            if (all instanceof java.util.Collection) return (java.util.Collection<?>) all;
        } catch (NoSuchMethodException ignored) {
        }
        Object all = world.getClass().getMethod("getObject").invoke(world);
        if (all instanceof java.util.Collection) return (java.util.Collection<?>) all;
        if (all instanceof java.util.Map) return ((java.util.Map<?, ?>) all).values();
        return java.util.Collections.emptyList();
    }

    private static Object invokeOpt(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        return fallback;
    }

    private static Object player(String name) throws Exception {
        Class<?> worldCl = find("com.lineage.server.world.World");
        Object world = worldCl.getMethod("get").invoke(null);
        return worldCl.getMethod("getPlayer", String.class).invoke(world, name);
    }

    /** 兜底類加載器：僅在系統 ClassLoader 找不到類時才建立（延遲初始化）。 */
    private static volatile ClassLoader fallbackLoader;

    private static Class<?> find(String name) {
        if (inst != null) {
            Class<?>[] loaded = inst.getAllLoadedClasses();
            for (int i = 0; i < loaded.length; i++) {
                if (name.equals(loaded[i].getName())) return loaded[i];
            }
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            Class<?> c = loadWithFreshLoader(name);
            if (c != null) return c;
            throw new IllegalStateException("class not loaded: " + name);
        }
    }

    /**
     * 兜底加載：若服務端 jar 在 JVM 啟動「之後」被替換過，系統 ClassLoader 快取的 jar
     * 中央目錄(central directory)會失效 —— 表現為所有「尚未載入過」的類都拋
     * ClassNotFoundException（而已載入的類不受影響，所以服務端看起來一切正常）。
     * <p>
     * 此處用一個全新的 URLClassLoader 指向同一批 classpath，重新打開 jar 檔案（讀回
     * 最新的中央目錄），即可正常載入。父委託仍指向系統 ClassLoader，因此已載入過的
     * 依賴類（例如 L1CommandExecutor）仍會解析成同一個 Class，反射呼叫不會有型別衝突。
     *
     * @return 載入成功返回 Class，失敗返回 null（由呼叫端拋原本的例外）
     */
    private static Class<?> loadWithFreshLoader(String name) {
        try {
            ClassLoader fl = fallbackLoader;
            if (fl == null) {
                synchronized (ServerAgent.class) {
                    if (fallbackLoader == null) {
                        String cp = System.getProperty("java.class.path", "");
                        String[] parts = cp.split(File.pathSeparator);
                        URL[] urls = new URL[parts.length];
                        int n = 0;
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i] == null || parts[i].isEmpty()) continue;
                            try {
                                urls[n++] = new File(parts[i]).toURI().toURL();
                            } catch (Exception ignore) {
                                // 路徑不合法就跳過
                            }
                        }
                        if (n == 0) return null;
                        if (n < urls.length) {
                            URL[] t = new URL[n];
                            System.arraycopy(urls, 0, t, 0, n);
                            urls = t;
                        }
                        fallbackLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
                        System.err.println("[GMAgent] 系統 ClassLoader 無法載入 " + name
                                + "（jar 可能在 JVM 啟動後被替換過），已啟用兜底 ClassLoader");
                    }
                    fl = fallbackLoader;
                }
            }
            return Class.forName(name, true, fl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(HttpExchange exchange) throws Exception {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), UTF8);
    }

    private static void reply(HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(UTF8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        } catch (Exception ignored) {
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parse(String json) {
        Map<String, String> map = new HashMap<String, String>();
        if (json == null) return map;
        int i = 0;
        while (i < json.length()) {
            int k1 = json.indexOf('"', i);
            if (k1 < 0) break;
            int k2 = json.indexOf('"', k1 + 1);
            if (k2 < 0) break;
            String key = json.substring(k1 + 1, k2);
            int colon = json.indexOf(':', k2 + 1);
            if (colon < 0) break;
            int p = colon + 1;
            while (p < json.length() && json.charAt(p) <= ' ') p++;
            String value;
            if (p < json.length() && json.charAt(p) == '"') {
                int v2 = json.indexOf('"', p + 1);
                if (v2 < 0) break;
                value = json.substring(p + 1, v2);
                i = v2 + 1;
            } else {
                int v2 = p;
                while (v2 < json.length() && json.charAt(v2) != ',' && json.charAt(v2) != '}') v2++;
                value = json.substring(p, v2).trim();
                i = v2;
            }
            map.put(key, value);
        }
        return map;
    }

    private static String str(Map<String, String> map, String key) {
        String v = map.get(key);
        return v == null ? "" : v;
    }

    private static int toInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
