package local.longtan.gmtool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppServer {
    private static final int FIRST_PORT = 17880;
    private static final int LAST_PORT = 17899;
    // 金币(40308)走服务端专属指令 .钱o(L1Adena)，而不是通用造物指令 .do
    private static final int ADENA_ITEM_ID = 40308;
    // 金币实际封顶 20 亿：服务端 S_InvList/S_ItemAmount 都做 Math.min(count, 2000000000)
    private static final int MAX_ADENA = 2_000_000_000;
    // 其它道具沿用 .do 的既有工具上限
    private static final int MAX_ITEM_COUNT = 1_000_000;
    private final InetAddress address;
    private final Database database;
    private final GameBridge gameBridge;
    private final ServerBridge serverBridge;
    private final AuditLog audit;
    private final PendingStore pending;
    private final MapTiles mapTiles;
    private final SpritePreview sprites;
    private final boolean remote;
    private final String token = UUID.randomUUID().toString();
    private HttpServer server;
    private ExecutorService executor;

    AppServer(InetAddress address, Database database, GameBridge gameBridge, ServerBridge serverBridge,
              AuditLog audit, PendingStore pending, MapTiles mapTiles, SpritePreview sprites, boolean remote) {
        this.address = address;
        this.database = database;
        this.gameBridge = gameBridge;
        this.serverBridge = serverBridge;
        this.audit = audit;
        this.pending = pending;
        this.mapTiles = mapTiles;
        this.sprites = sprites;
        this.remote = remote;
    }

    int start() throws IOException {
        IOException lastError = null;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                server = HttpServer.create(new InetSocketAddress(address, port), 0);
                registerContexts();
                executor = Executors.newFixedThreadPool(8, runnable -> {
                    Thread thread = new Thread(runnable, "gmtool-http");
                    thread.setDaemon(false);
                    return thread;
                });
                server.setExecutor(executor);
                server.start();
                return port;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new IOException("端口 " + FIRST_PORT + "-" + LAST_PORT + " 均不可用", lastError);
    }

    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void registerContexts() {
        server.createContext("/api/status", exchange -> api(exchange, false, this::status));
        server.createContext("/api/characters", exchange -> api(exchange, false, this::characters));
        server.createContext("/api/character", exchange -> api(exchange, false, this::character));
        server.createContext("/api/items", exchange -> api(exchange, false, this::items));
        server.createContext("/api/item-meta", exchange -> api(exchange, false, this::itemMeta));
        server.createContext("/api/npcs", exchange -> api(exchange, false, this::npcs));
        server.createContext("/api/npc-meta", exchange -> api(exchange, false, this::npcMeta));
        server.createContext("/api/shop", exchange -> api(exchange, false, this::shop));
        server.createContext("/api/pending", exchange -> api(exchange, false, this::pendingGet));
        server.createContext("/api/maps/refresh", exchange -> api(exchange, false, this::refreshMaps));
        server.createContext("/api/maps", exchange -> api(exchange, false, this::maps));
        server.createContext("/api/map/", this::mapResource);
        server.createContext("/api/preview/icon", exchange -> api(exchange, false, this::previewIcon));
        server.createContext("/api/preview/game-window", exchange -> api(exchange, false, this::previewGameWindow));
        server.createContext("/api/follow", exchange -> api(exchange, true, this::follow));
        server.createContext("/api/teleport", exchange -> api(exchange, true, this::teleport));
        server.createContext("/api/stats", exchange -> api(exchange, true, this::stats));
        server.createContext("/api/access", exchange -> api(exchange, true, this::access));
        server.createContext("/api/send-item", exchange -> api(exchange, true, this::sendItem));
        server.createContext("/api/create-item", exchange -> api(exchange, true, this::createItem));
        server.createContext("/api/create-npc", exchange -> api(exchange, true, this::createNpc));
        server.createContext("/api/save-shop", exchange -> api(exchange, true, this::saveShop));
        server.createContext("/api/copy-shop", exchange -> api(exchange, true, this::copyShop));
        server.createContext("/api/spawn-npc", exchange -> api(exchange, true, this::spawnNpc));
        server.createContext("/api/disable-spawns", exchange -> api(exchange, true, this::disableSpawns));
        server.createContext("/api/despawn-npc", exchange -> api(exchange, true, this::despawnNpcLive));
        server.createContext("/api/pending/cancel", exchange -> api(exchange, true, this::pendingCancel));
        server.createContext("/api/pending/undo", exchange -> api(exchange, true, this::pendingUndo));
        server.createContext("/api/pending/clear", exchange -> api(exchange, true, this::pendingClear));
        server.createContext("/api/send-command", exchange -> api(exchange, true, this::sendCommand));
        server.createContext("/api/shutdown", exchange -> api(exchange, true, this::shutdown));
        server.createContext("/", this::staticResource);
    }

    private Object status(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        Map<String, Object> status = new LinkedHashMap<>(database.status());
        status.put("gameWindow", gameBridge.isGameWindowAvailable());
        status.put("gameProcess", gameBridge.detectedName());
        status.putAll(serverBridge.status());
        status.put("token", token);
        status.put("version", "1.0.0");
        status.put("mode", remote ? "remote" : "local");
        status.put("pending", pending.openCount());
        return status;
    }

    private Object characters(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        Map<String, String> query = query(exchange);
        List<Map<String, Object>> rows = database.characters(query.getOrDefault("q", ""), query.getOrDefault("sort", ""),
                query.getOrDefault("online", ""), query.getOrDefault("type", ""));
        overlayLiveLocs(rows);
        return rows;
    }

    private Object character(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        long id = longValue(query(exchange).get("id"), -1);
        if (id <= 0) throw new IllegalArgumentException("缺少角色 ID");
        Map<String, Object> row = database.character(id);
        overlayLiveLocs(java.util.Collections.singletonList(row));
        return row;
    }

    private Object items(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        Map<String, String> query = query(exchange);
        int offset = intValue(query.get("offset"), 0);
        int limit = intValue(query.get("limit"), 80);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", database.searchItems(query.getOrDefault("q", ""), query.getOrDefault("category", "all"), offset, limit));
        response.put("total", database.countItems(query.getOrDefault("q", ""), query.getOrDefault("category", "all")));
        response.put("offset", offset);
        response.put("limit", limit);
        return response;
    }

    private Object itemMeta(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        return database.itemMeta();
    }

    private Object npcs(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        Map<String, String> query = query(exchange);
        int offset = intValue(query.get("offset"), 0);
        int limit = intValue(query.get("limit"), 80);
        Map<String, Object> response = new LinkedHashMap<>();
        String kind = query.getOrDefault("kind", "");
        response.put("rows", database.npcs(query.getOrDefault("q", ""), offset, limit, kind));
        response.put("total", database.countNpcs(query.getOrDefault("q", ""), kind));
        return response;
    }

    private Object npcMeta(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        return database.npcMeta();
    }

    private Object shop(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        int npcId = intValue(query(exchange).get("npcId"), -1);
        if (npcId <= 0) throw new IllegalArgumentException("缺少 npcId");
        return database.shop(npcId);
    }

    private Object pendingGet(HttpExchange exchange) {
        requireMethod(exchange, "GET");
        return pending.list();
    }

    private Object maps(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "GET");
        return database.maps(query(exchange).getOrDefault("q", ""));
    }

    private Object refreshMaps(HttpExchange exchange) throws SQLException {
        requireMethod(exchange, "POST");
        mapTiles.invalidateAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("maps", database.maps(""));
        return result;
    }

    private Object mapMeta(int mapId) throws SQLException {
        Map<String, Object> row = database.map(mapId);
        return mapTiles.meta(mapId,
                intValue(row.get("startX"), 0),
                intValue(row.get("startY"), 0),
                intValue(row.get("endX"), 0),
                intValue(row.get("endY"), 0),
                stringValue(row.get("name")));
    }

    private Object previewIcon(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        return sprites.icon(intValue(query(exchange).get("id"), 0));
    }

    private Object previewGameWindow(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        return gameBridge.captureWindow();
    }

    private Object follow(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        long targetId = longValue(body.get("targetId"), -1);
        long operatorId = longValue(body.get("operatorId"), -1);
        List<Long> selected = longList(body.get("selectedIds"));
        if (selected.isEmpty()) throw new IllegalArgumentException("先选定角色");
        Map<String, Object> target = database.character(targetId);
        overlayLiveLocs(java.util.Collections.singletonList(target));
        String targetName = String.valueOf(target.get("name"));
        boolean targetLive = Boolean.TRUE.equals(target.get("live")) || Boolean.TRUE.equals(target.get("online"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (long id : selected) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            if (id == targetId) {
                row.put("action", "skip");
                row.put("message", "勾选的是目标自己，不会移动");
                results.add(row);
                continue;
            }
            Map<String, Object> ch = database.character(id);
            overlayLiveLocs(java.util.Collections.singletonList(ch));
            boolean online = Boolean.TRUE.equals(ch.get("online")) || Boolean.TRUE.equals(ch.get("live"));
            if (online && targetLive) {
                String command = "跟o " + targetName;
                serverBridge.command(String.valueOf(ch.get("name")), command);
                audit.write("移到此人处", ch.get("name") + " → " + targetName + " / " + command + " (实时)");
                row.put("action", "follow");
                row.put("command", "." + command);
                row.put("message", "已跟到 " + targetName + " 当前格 "
                        + target.get("map") + ":" + target.get("x") + "," + target.get("y"));
            } else if (online) {
                String command = "传送o " + target.get("x") + " " + target.get("y") + " " + target.get("map");
                serverBridge.command(String.valueOf(ch.get("name")), command);
                audit.write("移到此人处", ch.get("name") + " → " + targetName + " / " + command);
                row.put("action", "follow");
                row.put("command", "." + command);
                row.put("message", "已把 " + ch.get("name") + " 传到 " + target.get("map") + ":"
                        + target.get("x") + "," + target.get("y") + "（对方离线，用存档坐标）");
            } else if (!online) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("x", target.get("x"));
                payload.put("y", target.get("y"));
                payload.put("map", target.get("map"));
                pending.add(id, String.valueOf(ch.get("name")), "loc", payload);
                row.put("action", "pending");
                row.put("message", "已进待办，该号下线后写入对方存档坐标（非实时）");
            } else {
                row.put("action", "blocked");
                row.put("message", "无法移动 " + ch.get("name"));
            }
            results.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("results", results);
        return response;
    }

    private Object teleport(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        int x = intValue(body.get("x"), 0);
        int y = intValue(body.get("y"), 0);
        int map = intValue(body.get("map"), 4);
        Map<String, Object> row = database.map(map);
        if (!mapTiles.inBounds(map, x, y,
                intValue(row.get("startX"), 0),
                intValue(row.get("startY"), 0),
                intValue(row.get("endX"), 0),
                intValue(row.get("endY"), 0))) {
            throw new IllegalArgumentException("坐标超出该地图范围");
        }
        long characterId = longValue(body.get("characterId"), -1);
        String who = onlineName(characterId);
        if (who == null) who = onlineName(longValue(body.get("operatorId"), -1));
        if (who != null) {
            serverBridge.ensure();
            String command = "传送o " + x + " " + y + " " + map;
            serverBridge.command(who, command);
            audit.write("传送", who + " / " + command);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("command", "." + command);
            ok.put("mode", "live");
            return ok;
        }
        if (characterId <= 0) {
            throw new IllegalStateException("先指定要传送的角色，或勾选在线角色");
        }
        Map<String, Object> ch = database.character(characterId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("x", x);
        payload.put("y", y);
        payload.put("map", map);
        pending.add(characterId, String.valueOf(ch.get("name")), "loc", payload);
        Map<String, Object> queued = new LinkedHashMap<>();
        queued.put("mode", "pending");
        queued.put("message", ch.get("name") + " 离线，已记下 " + map + ":" + x + "," + y);
        return queued;
    }

    private Object stats(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        long characterId = longValue(body.get("characterId"), -1);
        Map<String, Object> character = database.character(characterId);
        int hp = intValue(body.get("hp"), ((Number) character.get("hp")).intValue());
        int maxHp = intValue(body.get("maxHp"), ((Number) character.get("maxHp")).intValue());
        int mp = intValue(body.get("mp"), ((Number) character.get("mp")).intValue());
        int maxMp = intValue(body.get("maxMp"), ((Number) character.get("maxMp")).intValue());
        boolean online = Boolean.TRUE.equals(character.get("online"));
        if (online) {
            serverBridge.ensure();
            String name = String.valueOf(character.get("name"));
            String opName = onlineName(longValue(body.get("operatorId"), -1));
            if (opName == null) opName = name;
            serverBridge.command(opName, "角色更改o " + name + " HP " + maxHp);
            serverBridge.command(opName, "角色更改o " + name + " MP " + maxMp);
            audit.write("血蓝指令", name + " HP/MP 上限 " + maxHp + "/" + maxMp);
            return Map.of("ok", true, "mode", "command");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hp", hp);
        payload.put("maxHp", maxHp);
        payload.put("mp", mp);
        payload.put("maxMp", maxMp);
        pending.add(characterId, String.valueOf(character.get("name")), "hpmp", payload);
        return Map.of("ok", true, "mode", "pending");
    }

    private Object access(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        long characterId = longValue(body.get("characterId"), -1);
        int level = intValue(body.get("level"), 0);
        Map<String, Object> character = database.character(characterId);
        if (Boolean.TRUE.equals(character.get("online"))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("level", level);
            pending.add(characterId, String.valueOf(character.get("name")), "access", payload);
            return Map.of("ok", true, "mode", "pending");
        }
        database.setGmAccess(characterId, level);
        return Map.of("ok", true, "mode", "written");
    }

    private Object sendItem(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        String mode = stringValue(body.get("mode"));
        int itemId = intValue(body.get("itemId"), -1);
        boolean isAdena = itemId == ADENA_ITEM_ID;
        // 金币走专属指令，不吃 .do 的 100w 工具上限（服务端 L1Adena 无上限，实际封顶 20 亿）
        int count = bounded(intValue(body.get("count"), 1), 1, isAdena ? MAX_ADENA : MAX_ITEM_COUNT, "数量");
        int enchant = bounded(intValue(body.get("enchant"), 0), -20, 30, "强化");
        Database.Item item = database.requireItem(itemId);
        if ("warehouse".equals(mode)) {
            Map<String, Object> character = database.character(longValue(body.get("characterId"), -1));
            serverBridge.ensure();
            String op = onlineName(longValue(body.get("operatorId"), -1));
            // 仓库侧没有账号级金币指令，金币也只能走 .给予o(L1CN)
            String command = "给予o " + character.get("account") + " " + itemId + " " + count;
            serverBridge.command(op, command);
            audit.write("仓库给予", character.get("account") + " / " + item.name + " x" + count);
            return Map.of("ok", true, "command", command);
        }
        if (!isAdena && !item.stackable && count > 10) {
            throw new IllegalArgumentException("不可堆叠物品 .do 一次最多 10 件");
        }
        serverBridge.ensure();
        String who = onlineName(longValue(body.get("operatorId"), -1));
        if (who == null) who = onlineName(longValue(body.get("characterId"), -1));
        if (who == null) {
            throw new IllegalStateException("刷进背包需要一个在线角色接物品（不必是GM号）；离线请用仓库");
        }
        if (isAdena) {
            // 金币专属链路：.钱o -> L1Adena.storeItem(L1ItemId.ADENA, count)
            String command = "钱o " + count;
            serverBridge.command(who, command);
            audit.write("创造金币", "钱o " + count);
            return Map.of("ok", true, "command", command);
        }
        String command = "do " + itemId + " " + count + " " + enchant;
        serverBridge.command(who, command);
        audit.write("创造物品", item.name + "(" + itemId + ") x" + count + " +" + enchant);
        return Map.of("ok", true, "command", command);
    }

    private Object createItem(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, Object> created = database.createItem(body(exchange));
        created.put("hint", "新物品需重启游戏服后才进内存（.道具表o 类可能未实装）");
        return created;
    }

    private Object createNpc(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        Map<String, Object> created = database.createNpc(body);
        created.put("hint", "新 NPC 需重启游戏服后才能刷出（.NPC表o 类可能未实装）");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("npcId", created.get("npcId"));
        payload.put("name", created.get("name"));
        payload.put("wroteNpcaction", created.get("wroteNpcaction"));
        pending.addUndoable(0, "GM", "create-npc", payload);
        return created;
    }

    @SuppressWarnings("unchecked")
    private Object saveShop(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        int npcId = intValue(body.get("npcId"), -1);
        Object raw = body.get("items");
        if (!(raw instanceof List)) throw new IllegalArgumentException("货单格式无效");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object row : (List<?>) raw) {
            if (row instanceof Map) items.add((Map<String, Object>) row);
        }
        List<Map<String, Object>> previous = database.shop(npcId);
        boolean hadAction = database.npcActionExists(npcId);
        database.saveShop(npcId, items, false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("npcId", npcId);
        payload.put("previousItems", previous);
        payload.put("wroteNpcaction", !hadAction);
        pending.addUndoable(0, "GM", "save-shop", payload);
        return Map.of("ok", true, "hint", "货单需重启或尝试 .商店表o");
    }

    private Object copyShop(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        int toNpc = intValue(body.get("toNpcId"), -1);
        List<Map<String, Object>> previous = database.shop(toNpc);
        boolean hadAction = database.npcActionExists(toNpc);
        database.copyShop(intValue(body.get("fromNpcId"), 90043), toNpc);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("npcId", toNpc);
        payload.put("previousItems", previous);
        payload.put("wroteNpcaction", !hadAction);
        pending.addUndoable(0, "GM", "save-shop", payload);
        return Map.of("ok", true);
    }

    private Object spawnNpc(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        int npcId = intValue(body.get("npcId"), -1);
        boolean hasHeading = body.containsKey("heading") && body.get("heading") != null;
        int heading = hasHeading ? intValue(body.get("heading"), 0) & 7 : -1;
        boolean live = Boolean.TRUE.equals(body.get("live"));
        long operatorId = longValue(body.get("operatorId"), -1);
        boolean monsterUi = "monster".equals(stringValue(body.get("kind")));
        long characterId = longValue(body.get("characterId"), operatorId);
        Map<String, Object> facing = null;
        if (!monsterUi && characterId > 0) {
            facing = database.character(characterId);
            overlayLiveLocs(java.util.Collections.singletonList(facing));
            heading = intValue(facing.get("heading"), 0) & 7;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (live) {
            serverBridge.ensure();
            String opName = onlineName(operatorId);
            int x = intValue(body.get("x"), 0);
            int y = intValue(body.get("y"), 0);
            int map = intValue(body.get("map"), 0);
            if (x <= 0 || y <= 0) {
                Map<String, Object> loc = facing;
                if (loc == null) {
                    long locId = characterId > 0 ? characterId : operatorId;
                    if (locId > 0) {
                        loc = database.character(locId);
                        overlayLiveLocs(java.util.Collections.singletonList(loc));
                    }
                }
                if (loc != null) {
                    x = ((Number) loc.get("x")).intValue();
                    y = ((Number) loc.get("y")).intValue();
                    map = ((Number) loc.get("map")).intValue();
                    if (monsterUi && heading < 0) heading = intValue(loc.get("heading"), 5) & 7;
                }
            }
            if (heading < 0) heading = monsterUi ? 5 : 0;
            Map<String, Object> spawned;
            try {
                spawned = serverBridge.spawnLive(opName, npcId, x, y, map, heading);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("脚底测试失败：" + e.getMessage(), e);
            }
            audit.write("脚底测试NPC", "obj=" + spawned.get("objId") + " npc=" + npcId + " h=" + heading);
            Map<String, Object> livePayload = new LinkedHashMap<>();
            livePayload.put("objId", spawned.get("objId"));
            livePayload.put("npcId", npcId);
            livePayload.put("operator", opName);
            livePayload.put("x", spawned.get("x"));
            livePayload.put("y", spawned.get("y"));
            livePayload.put("map", spawned.get("map"));
            livePayload.put("heading", heading);
            pending.addUndoable(operatorId, opName == null ? "GM" : opName, "spawn-live", livePayload);
            result.putAll(spawned);
            result.put("mode", "spawn-live");
            result.put("hint", "只当场一只，待办可按对象撤回");
            return result;
        }
        Map<String, Object> character = facing != null ? facing
                : database.character(longValue(body.get("characterId"), -1));
        if (facing == null) overlayLiveLocs(java.util.Collections.singletonList(character));
        int x = ((Number) character.get("x")).intValue();
        int y = ((Number) character.get("y")).intValue();
        int map = ((Number) character.get("map")).intValue();
        int usedHeading = !monsterUi
                ? (heading >= 0 ? heading & 7 : intValue(character.get("heading"), 0) & 7)
                : (heading >= 0 ? heading : (intValue(character.get("heading"), 5) & 7));
        result.putAll(database.spawnAt(npcId, x, y, map, usedHeading, "gm-" + character.get("name")));
        result.put("mode", "spawnlist");
        Map<String, Object> spawned = null;
        String liveError = null;
        try {
            serverBridge.ensure();
            String opName = onlineName(operatorId > 0 ? operatorId : longValue(body.get("characterId"), -1));
            spawned = serverBridge.spawnLive(opName, npcId, x, y, map, usedHeading);
        } catch (Exception e) {
            liveError = e.getMessage();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spawnId", result.get("spawnId"));
        payload.put("npcId", npcId);
        payload.put("x", x);
        payload.put("y", y);
        payload.put("map", map);
        payload.put("heading", usedHeading);
        payload.put("location", "gm-" + character.get("name"));
        payload.put("table", result.get("table"));
        payload.put("monster", result.get("monster"));
        if (spawned != null && spawned.get("objId") != null) {
            payload.put("objId", spawned.get("objId"));
            result.put("objId", spawned.get("objId"));
            audit.write("刷新NPC当场放置", "obj=" + spawned.get("objId") + " npc=" + npcId);
        }
        pending.addUndoable(longValue(body.get("characterId"), 0),
                String.valueOf(character.get("name")), "spawnlist", payload);
        if (spawned != null) {
            result.put("hint", Boolean.TRUE.equals(character.get("live"))
                    ? "已写入刷新点并当场放置，待办可一并撤回"
                    : "已写入刷新点并当场放置（坐标来自存档），待办可一并撤回");
        } else {
            result.put("hint", "已写入刷新点，重启后仍会刷。当场放置失败：" + liveError);
        }
        return result;
    }

    private Object disableSpawns(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        int npcId = intValue(body(exchange).get("npcId"), -1);
        List<Map<String, Object>> previous = database.disableSpawns(npcId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("npcId", npcId);
        payload.put("previous", previous);
        pending.addUndoable(0, "GM", "disable-spawns", payload);
        return Map.of("ok", true);
    }

    private Object despawnNpcLive(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        int npcId = intValue(body.get("npcId"), -1);
        int map = intValue(body.get("map"), -1);
        int x = intValue(body.get("x"), 0);
        int y = intValue(body.get("y"), 0);
        int radius = intValue(body.get("radius"), 0);
        Map<String, Object> gone = serverBridge.despawnNpc(npcId, map, x, y, radius);
        audit.write("移走NPC", "npc=" + npcId + " map=" + map + " removed=" + gone.get("removed"));
        return gone;
    }

    private Object pendingClear(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        int n = pending.clearAll();
        audit.write("清空待办", n + " 条");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("cleared", n);
        return result;
    }

    private Object pendingCancel(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        String id = stringValue(body(exchange).get("id"));
        if (!pending.cancel(id)) throw new IllegalStateException("无法取消该待办");
        return Map.of("ok", true);
    }

    @SuppressWarnings("unchecked")
    private Object pendingUndo(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        String id = stringValue(body(exchange).get("id"));
        if (pending.cancel(id)) {
            return Map.of("ok", true, "mode", "cancelled");
        }
        Map<String, Object> task = pending.undoable(id);
        if (task == null) throw new IllegalStateException("该记录不能撤回");
        String type = String.valueOf(task.get("type"));
        Map<String, Object> payload = task.get("payload") instanceof Map
                ? (Map<String, Object>) task.get("payload") : new LinkedHashMap<>();
        String hint = undoTask(type, payload);
        if (!pending.markUndone(id)) throw new IllegalStateException("撤回状态写入失败");
        audit.write("撤回", type + " / " + task.get("detail"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("mode", "undone");
        result.put("hint", hint);
        return result;
    }

    @SuppressWarnings("unchecked")
    private String undoTask(String type, Map<String, Object> payload) throws Exception {
        switch (type) {
            case "spawn-live": {
                int objId = intValue(payload.get("objId"), 0);
                int npcId = intValue(payload.get("npcId"), 0);
                Map<String, Object> gone = serverBridge.despawn(objId, npcId);
                if (!Boolean.TRUE.equals(gone.get("ok"))) {
                    throw new IllegalStateException(String.valueOf(gone.get("error")));
                }
                return "已从场上移除该对象";
            }
            case "spawnlist": {
                int spawnId = intValue(payload.get("spawnId"), 0);
                int npcId = intValue(payload.get("npcId"), 0);
                int objId = intValue(payload.get("objId"), 0);
                boolean removedLive = false;
                if (objId > 0) {
                    try {
                        Map<String, Object> gone = serverBridge.despawn(objId, npcId);
                        removedLive = Boolean.TRUE.equals(gone.get("ok"));
                    } catch (Exception ignored) {
                    }
                }
                try {
                    Map<String, Object> gone = serverBridge.despawnSpawn(spawnId, npcId);
                    if (intValue(gone.get("removed"), 0) > 0) removedLive = true;
                } catch (Exception ignored) {
                }
                String table = stringValue(payload.get("table"));
                database.deleteSpawn(spawnId, table == null || table.isEmpty() ? "spawnlist_npc" : table);
                return removedLive ? "已删除刷新点并移走当场那只" : "已删除该刷新点";
            }
            case "create-npc": {
                database.undoCreateNpc(intValue(payload.get("npcId"), 0),
                        Boolean.TRUE.equals(payload.get("wroteNpcaction")));
                return "已删除该 NPC 模板";
            }
            case "disable-spawns": {
                Object raw = payload.get("previous");
                List<Map<String, Object>> previous = new ArrayList<>();
                if (raw instanceof List) {
                    for (Object row : (List<?>) raw) {
                        if (row instanceof Map) previous.add((Map<String, Object>) row);
                    }
                }
                database.restoreSpawnCounts(previous);
                return "已按快照恢复 count（活体需刷新或重启）";
            }
            case "save-shop": {
                int npcId = intValue(payload.get("npcId"), 0);
                Object raw = payload.get("previousItems");
                List<Map<String, Object>> previous = new ArrayList<>();
                if (raw instanceof List) {
                    for (Object row : (List<?>) raw) {
                        if (row instanceof Map) previous.add((Map<String, Object>) row);
                    }
                }
                database.saveShop(npcId, previous, false);
                return "已恢复货单，游戏内需重启或 .商店表o";
            }
            default:
                throw new IllegalStateException("该类型不能撤回");
        }
    }

    private Object sendCommand(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<?, ?> body = body(exchange);
        serverBridge.ensure();
        String command = stringValue(body.get("command"));
        if (command.startsWith(".")) command = command.substring(1);
        String op = onlineName(longValue(body.get("operatorId"), -1));
        serverBridge.command(op, command);
        audit.write("指令", command);
        return Map.of("command", "." + command);
    }

    private Object shutdown(HttpExchange exchange) {
        requireMethod(exchange, "POST");
        audit.write("退出工具", "用户从页面结束进程");
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(180);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "gmtool-exit");
        exit.setDaemon(true);
        exit.start();
        return Map.of("ok", true);
    }

    private Map<String, Object> requireOnlineOperator(long characterId, int ignoredRequiredLevel) throws SQLException {
        Map<String, Object> character = database.character(characterId);
        if (!Boolean.TRUE.equals(character.get("online"))) {
            throw new IllegalStateException("操作者不在线");
        }
        serverBridge.ensure();
        return character;
    }

    private void overlayLiveLocs(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        Map<Long, Map<String, Object>> live = new LinkedHashMap<>();
        try {
            for (Map<String, Object> p : serverBridge.liveLocs()) {
                long id = longValue(p.get("id"), -1);
                if (id > 0) live.put(id, p);
            }
        } catch (Exception ignored) {
            return;
        }
        if (live.isEmpty()) return;
        for (Map<String, Object> row : rows) {
            Map<String, Object> loc = live.get(longValue(row.get("id"), -1));
            if (loc == null) continue;
            row.put("x", intValue(loc.get("x"), 0));
            row.put("y", intValue(loc.get("y"), 0));
            row.put("map", intValue(loc.get("map"), 0));
            row.put("heading", intValue(loc.get("heading"), 0));
            row.put("online", true);
            row.put("live", true);
        }
    }

    private String onlineName(long characterId) throws SQLException {
        if (characterId <= 0) return null;
        Map<String, Object> character = database.character(characterId);
        if (!Boolean.TRUE.equals(character.get("online"))) return null;
        return String.valueOf(character.get("name"));
    }

    private void mapResource(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 400, error("不支持的请求方式"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String rest = path.substring("/api/map/".length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0) {
                sendJson(exchange, 400, error("缺少地图编号"));
                return;
            }
            int mapId = intValue(rest.substring(0, dot), -1);
            if (mapId < 0) {
                sendJson(exchange, 400, error("地图编号无效"));
                return;
            }
            String ext = rest.substring(dot + 1).toLowerCase();
            Map<String, Object> row = database.map(mapId);
            int startX = intValue(row.get("startX"), 0);
            int startY = intValue(row.get("startY"), 0);
            int endX = intValue(row.get("endX"), 0);
            int endY = intValue(row.get("endY"), 0);
            if ("json".equals(ext)) {
                sendJson(exchange, 200, mapTiles.meta(mapId, startX, startY, endX, endY, stringValue(row.get("name"))));
                return;
            }
            if ("png".equals(ext)) {
                boolean force = "1".equals(query(exchange).get("force")) || "true".equalsIgnoreCase(query(exchange).get("force"));
                byte[] bytes = mapTiles.png(mapId, startX, startY, endX, endY, force);
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                return;
            }
            sendJson(exchange, 404, error("不支持的地图资源"));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, error(isDbDown(e)
                    ? (remote ? "未检测到远端服务端与数据库" : "未检测到本地服务端与数据库")
                    : "地图读取失败"));
        } finally {
            exchange.close();
        }
    }

    private void api(HttpExchange exchange, boolean protectedCall, ApiHandler handler) throws IOException {
        try {
            if (protectedCall && !token.equals(exchange.getRequestHeaders().getFirst("X-GM-Token"))) {
                sendJson(exchange, 403, error("请求令牌无效，请刷新页面"));
                return;
            }
            sendJson(exchange, 200, handler.handle(exchange));
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendJson(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, error(publicError(e)));
        } finally {
            exchange.close();
        }
    }

    private void staticResource(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";
        if (path.contains("..")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        String resource = "/web" + path;
        try (InputStream input = AppServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = input.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", contentType(path));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; font-src 'self'");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private Map<?, ?> body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(512_000);
        return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".png")) return "image/png";
        return "text/html; charset=utf-8";
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equals(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("不支持的请求方式");
        }
    }

    private static int bounded(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + "必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        try {
            return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> longList(Object value) {
        List<Long> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                long id = longValue(item, -1);
                if (id > 0) result.add(id);
            }
        }
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static Map<String, String> error(String message) {
        return Collections.singletonMap("error", message == null ? "未知错误" : message);
    }

    private String publicError(Throwable error) {
        if (isDbDown(error)) return remote ? "未检测到远端服务端与数据库" : "未检测到本地服务端与数据库";
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return "操作失败";
        return "操作失败：" + message;
    }

    private static boolean isDbDown(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String name = current.getClass().getName();
            String message = current.getMessage() == null ? "" : current.getMessage();
            if (name.contains("CommunicationsException") || name.contains("ConnectException")
                    || name.contains("SocketTimeoutException") || name.contains("CJCommunicationsException")) {
                return true;
            }
            if (message.contains("Communications link failure")
                    || message.contains("The last packet sent successfully")
                    || message.contains("Could not create connection")
                    || message.contains("Connection refused")
                    || message.contains("connect timed out")
                    || message.contains("No operations allowed after connection closed")) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface ApiHandler {
        Object handle(HttpExchange exchange) throws Exception;
    }
}
