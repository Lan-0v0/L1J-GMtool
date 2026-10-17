package local.longtan.gmtool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

final class PendingStore {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path path;
    private final Database database;
    private final AuditLog audit;
    private final List<Map<String, Object>> tasks = new CopyOnWriteArrayList<>();
    private volatile Thread worker;
    private volatile boolean running;

    PendingStore(Path path, Database database, AuditLog audit) throws IOException {
        this.path = path;
        this.database = database;
        this.audit = audit;
        load();
    }

    synchronized Map<String, Object> add(long characterId, String characterName, String type, Map<String, Object> payload) {
        return add(characterId, characterName, type, payload, "wait-offline");
    }

    synchronized Map<String, Object> addUndoable(long characterId, String characterName, String type, Map<String, Object> payload) {
        return add(characterId, characterName, type, payload, "undoable");
    }

    private Map<String, Object> add(long characterId, String characterName, String type, Map<String, Object> payload, String status) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", UUID.randomUUID().toString());
        task.put("characterId", characterId);
        task.put("characterName", characterName);
        task.put("type", type);
        task.put("payload", payload == null ? new LinkedHashMap<>() : payload);
        task.put("status", status);
        task.put("created", FORMAT.format(LocalDateTime.now()));
        task.put("detail", describe(type, payload));
        tasks.add(0, task);
        saveQuiet();
        return task;
    }

    synchronized boolean cancel(String id) {
        Iterator<Map<String, Object>> it = tasks.iterator();
        while (it.hasNext()) {
            Map<String, Object> task = it.next();
            if (id.equals(String.valueOf(task.get("id")))) {
                String status = String.valueOf(task.get("status"));
                if ("wait-offline".equals(status) || "cooldown".equals(status)) {
                    task.put("status", "cancelled");
                    saveQuiet();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    synchronized Map<String, Object> undoable(String id) {
        for (Map<String, Object> task : tasks) {
            if (!id.equals(String.valueOf(task.get("id")))) continue;
            if (!"undoable".equals(String.valueOf(task.get("status")))) return null;
            return task;
        }
        return null;
    }

    synchronized boolean markUndone(String id) {
        for (Map<String, Object> task : tasks) {
            if (!id.equals(String.valueOf(task.get("id")))) continue;
            if (!"undoable".equals(String.valueOf(task.get("status")))) return false;
            task.put("status", "undone");
            task.put("executed", FORMAT.format(LocalDateTime.now()));
            saveQuiet();
            return true;
        }
        return false;
    }

    synchronized int clearAll() {
        int n = tasks.size();
        tasks.clear();
        saveQuiet();
        return n;
    }

    List<Map<String, Object>> list() {
        return new ArrayList<>(tasks);
    }

    int openCount() {
        int n = 0;
        for (Map<String, Object> task : tasks) {
            String status = String.valueOf(task.get("status"));
            if ("wait-offline".equals(status) || "cooldown".equals(status) || "undoable".equals(status)) n++;
        }
        return n;
    }

    void start() {
        running = true;
        worker = new Thread(this::loop, "gmtool-pending");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                tick();
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void tick() {
        for (Map<String, Object> task : tasks) {
            String status = String.valueOf(task.get("status"));
            if (!"wait-offline".equals(status)) {
                continue;
            }
            long characterId = ((Number) task.get("characterId")).longValue();
            try {
                if (!database.isOffline(characterId)) {
                    continue;
                }
                apply(task);
                task.put("status", "done");
                task.put("executed", FORMAT.format(LocalDateTime.now()));
                audit.write("待办执行", task.get("characterName") + " / " + task.get("type") + " / " + task.get("detail"));
                saveQuiet();
            } catch (Exception e) {
                task.put("status", "failed");
                task.put("error", e.getMessage());
                saveQuiet();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> task) throws Exception {
        long characterId = ((Number) task.get("characterId")).longValue();
        String type = String.valueOf(task.get("type"));
        Map<String, Object> payload = (Map<String, Object>) task.get("payload");
        switch (type) {
            case "loc":
                database.setLocation(characterId,
                        intValue(payload.get("x")),
                        intValue(payload.get("y")),
                        intValue(payload.get("map")));
                break;
            case "hpmp":
                database.setHpMp(characterId,
                        intValue(payload.get("hp")),
                        intValue(payload.get("maxHp")),
                        intValue(payload.get("mp")),
                        intValue(payload.get("maxMp")));
                break;
            case "access":
                database.setGmAccess(characterId, intValue(payload.get("level")));
                break;
            default:
                throw new IllegalArgumentException("未知待办类型 " + type);
        }
    }

    private static String describe(String type, Map<String, Object> payload) {
        if (payload == null) return type;
        if ("loc".equals(type)) {
            return "坐标 " + payload.get("map") + ":" + payload.get("x") + "," + payload.get("y")
                    + "（目标存档点，非实时）";
        }
        if ("hpmp".equals(type)) {
            return "血 " + payload.get("hp") + "/" + payload.get("maxHp")
                    + " 蓝 " + payload.get("mp") + "/" + payload.get("maxMp");
        }
        if ("access".equals(type)) {
            return "权限 " + payload.get("level");
        }
        if ("spawn-live".equals(type)) {
            return "脚底 obj=" + payload.get("objId") + " npc=" + payload.get("npcId");
        }
        if ("spawnlist".equals(type)) {
            String text = ("spawnlist".equals(String.valueOf(payload.get("table"))) ? "刷怪点 #" : "刷新点 #")
                    + payload.get("spawnId") + " npc=" + payload.get("npcId")
                    + " " + payload.get("map") + ":" + payload.get("x") + "," + payload.get("y");
            if (payload.get("objId") != null) text += " 当场 obj=" + payload.get("objId");
            return text;
        }
        if ("create-npc".equals(type)) {
            return "新建NPC " + payload.get("npcId") + " " + payload.get("name");
        }
        if ("disable-spawns".equals(type)) {
            return "count=0 npc=" + payload.get("npcId");
        }
        if ("save-shop".equals(type)) {
            return "货单 npc=" + payload.get("npcId");
        }
        return type;
    }

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) return;
        Object parsed = Json.parse(raw);
        if (parsed instanceof List) {
            for (Object item : (List<?>) parsed) {
                if (item instanceof Map) {
                    tasks.add((Map<String, Object>) item);
                }
            }
        }
    }

    private synchronized void saveQuiet() {
        try {
            Files.writeString(path, Json.stringify(tasks), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Unable to write pending.json: " + e.getMessage());
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}
