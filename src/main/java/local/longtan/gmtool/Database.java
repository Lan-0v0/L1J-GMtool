package local.longtan.gmtool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

final class Database {
    static final String[] CLASS_NAMES = {
            "王族", "骑士", "妖精", "法师", "黑暗妖精", "龙骑士", "幻术师"
    };
    private final AuditLog audit;
    private final Path configPath;
    private final String url;
    private final String login;
    private final String password;
    private volatile List<Item> itemCache;
    private final ClientStrings clientStrings;

    Database(Path home, AuditLog audit) throws IOException, ClassNotFoundException {
        this.audit = audit;
        this.clientStrings = ClientStrings.load(home.resolve("..").resolve("龙潭天堂").normalize());
        this.configPath = home.resolve("..").resolve("服务端").resolve("config").resolve("sql.properties").normalize();
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("找不到服务端数据库配置：" + configPath);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }
        String host = required(properties, "URL1");
        String schema = required(properties, "URL2");
        String extra = properties.getProperty("URL3", "").trim();
        this.url = normalizeUrl(host + schema + extra);
        this.login = required(properties, "Login");
        this.password = properties.getProperty("Password", "");
        Class.forName("com.mysql.jdbc.Driver");
    }

    Map<String, Object> status() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("database", connection.getCatalog());
            result.put("databaseVersion", connection.getMetaData().getDatabaseProductVersion());
            result.put("onlineCharacters", scalarInt(statement, "SELECT COUNT(*) FROM characters WHERE OnlineStatus <> 0"));
            result.put("accounts", scalarInt(statement, "SELECT COUNT(*) FROM accounts"));
            result.put("items", items().size());
            result.put("npcs", scalarInt(statement, "SELECT COUNT(*) FROM npc"));
            result.put("configSource", configPath.getFileName().toString());
            return result;
        }
    }

    List<Map<String, Object>> characters(String query, String sort, String online, String type) throws SQLException {
        String sql = "SELECT c.objid,c.char_name,c.account_name,c.Type,c.level,c.Exp,c.MaxHp,c.MaxMp,c.CurHp,c.CurMp,"
                + "c.AccessLevel,c.OnlineStatus,c.MapID,c.LocX,c.LocY,c.Heading,"
                + "a.access_level account_access,a.banned "
                + "FROM characters c JOIN accounts a ON a.login=c.account_name "
                + "WHERE (c.char_name LIKE ? OR c.account_name LIKE ?)";
        if ("1".equals(online)) sql += " AND c.OnlineStatus<>0";
        if ("0".equals(online)) sql += " AND c.OnlineStatus=0";
        if ("none".equals(online)) sql += " AND 1=0";
        if ("1,0".equals(online) || "0,1".equals(online)) { /* 两个筛选均开时不加限制 */ }
        if (type != null && !type.isEmpty()) sql += " AND c.Type=?";
        sql += " ORDER BY " + characterOrder(sort) + " LIMIT 2000";
        String pattern = "%" + (query == null ? "" : query.trim()) + "%";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            if (type != null && !type.isEmpty()) {
                statement.setInt(3, Integer.parseInt(type));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(characterRow(rs));
                }
            }
        }
        return rows;
    }

    Map<String, Object> character(long id) throws SQLException {
        String sql = "SELECT c.objid,c.char_name,c.account_name,c.Type,c.level,c.Exp,c.MaxHp,c.MaxMp,c.CurHp,c.CurMp,"
                + "c.AccessLevel,c.OnlineStatus,c.MapID,c.LocX,c.LocY,c.Heading,"
                + "a.access_level account_access,a.banned "
                + "FROM characters c JOIN accounts a ON a.login=c.account_name WHERE c.objid=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("角色不存在");
                return characterRow(rs);
            }
        }
    }

    boolean isOffline(long characterId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT c.OnlineStatus,a.banned FROM characters c JOIN accounts a ON a.login=c.account_name WHERE c.objid=?")) {
            statement.setLong(1, characterId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt("OnlineStatus") == 0;
            }
        }
    }

    void writeGmAccess(String account, String characterName, int level) throws SQLException {
        if (level < 0 || level > 200) throw new IllegalArgumentException("权限必须在 0 到 200");
        try (Connection connection = open()) {
            try (PreparedStatement updateAccount = connection.prepareStatement(
                    "UPDATE accounts SET access_level=? WHERE login=?");
                 PreparedStatement updateCharacter = connection.prepareStatement(
                         "UPDATE characters SET AccessLevel=? WHERE char_name=?")) {
                updateAccount.setInt(1, level);
                updateAccount.setString(2, account);
                updateAccount.executeUpdate();
                updateCharacter.setInt(1, level);
                updateCharacter.setString(2, characterName);
                updateCharacter.executeUpdate();
            }
        }
        audit.write("权限", characterName + " / " + account + " / level=" + level + " / forced");
    }

    void setGmAccess(long characterId, int level) throws SQLException {
        if (level < 0 || level > 200) throw new IllegalArgumentException("权限必须在 0 到 200");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String account;
                String name;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT c.account_name,c.char_name,c.OnlineStatus FROM characters c WHERE c.objid=? FOR UPDATE")) {
                    lock.setLong(1, characterId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("角色不存在");
                        if (rs.getInt("OnlineStatus") != 0) {
                            throw new IllegalStateException("角色仍在线，待办未执行");
                        }
                        account = rs.getString("account_name");
                        name = rs.getString("char_name");
                    }
                }
                try (PreparedStatement updateAccount = connection.prepareStatement(
                        "UPDATE accounts SET access_level=? WHERE login=?");
                     PreparedStatement updateCharacter = connection.prepareStatement(
                             "UPDATE characters SET AccessLevel=? WHERE objid=? AND OnlineStatus=0")) {
                    updateAccount.setInt(1, level);
                    updateAccount.setString(2, account);
                    updateAccount.executeUpdate();
                    updateCharacter.setInt(1, level);
                    updateCharacter.setLong(2, characterId);
                    if (updateCharacter.executeUpdate() == 0) {
                        throw new IllegalStateException("角色在写入瞬间上线，已中止");
                    }
                }
                connection.commit();
                audit.write("权限", name + " / " + account + " / level=" + level);
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void setLocation(long characterId, int x, int y, int map) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE characters SET LocX=?,LocY=?,MapID=? WHERE objid=? AND OnlineStatus=0")) {
            statement.setInt(1, x);
            statement.setInt(2, y);
            statement.setInt(3, map);
            statement.setLong(4, characterId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalStateException("角色在线或已不存在，坐标未写入");
            }
        }
    }

    void setHpMp(long characterId, int hp, int maxHp, int mp, int maxMp) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE characters SET CurHp=?,MaxHp=?,CurMp=?,MaxMp=? WHERE objid=? AND OnlineStatus=0")) {
            statement.setInt(1, clamp(hp, 1, 20000));
            statement.setInt(2, clamp(maxHp, 1, 20000));
            statement.setInt(3, clamp(mp, 0, 20000));
            statement.setInt(4, clamp(maxMp, 0, 20000));
            statement.setLong(5, characterId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalStateException("角色在线或已不存在，血蓝未写入");
            }
        }
    }

    List<Item> searchItems(String query, String category, int offset, int limit) throws SQLException {
        String normalized = simplify(query).toLowerCase(Locale.ROOT).replace(" ", "");
        String selected = category == null ? "all" : category;
        return items().stream()
                .filter(item -> "all".equals(selected) || item.category.equals(selected))
                .filter(item -> normalized.isEmpty()
                        || Integer.toString(item.id).contains(normalized)
                        || item.searchText.contains(normalized))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, Math.min(limit, 200)))
                .collect(Collectors.toList());
    }

    int countItems(String query, String category) throws SQLException {
        String normalized = simplify(query).toLowerCase(Locale.ROOT).replace(" ", "");
        String selected = category == null ? "all" : category;
        return (int) items().stream()
                .filter(item -> "all".equals(selected) || item.category.equals(selected))
                .filter(item -> normalized.isEmpty()
                        || Integer.toString(item.id).contains(normalized)
                        || item.searchText.contains(normalized))
                .count();
    }

    Item requireItem(int id) throws SQLException {
        return items().stream().filter(item -> item.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("物品 ID 不存在"));
    }

    Map<String, Object> map(int id) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT mapid,locationname,startX,endX,startY,endY FROM mapids WHERE mapid=?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", "地图 " + id);
        row.put("parent", "未分类");
        row.put("startX", 0);
        row.put("endX", 0);
        row.put("startY", 0);
        row.put("endY", 0);
        return row;
    }

    List<Map<String, Object>> maps(String query) throws SQLException {
        String sql = "SELECT mapid,locationname,startX,endX,startY,endY FROM mapids "
                + "WHERE CAST(mapid AS CHAR) LIKE ? OR locationname LIKE ? ORDER BY mapid";
        String pattern = "%" + simplify(query) + "%";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }
        return result;
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        int id = rs.getInt("mapid");
        String name = simplify(rs.getString("locationname"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name.isEmpty() ? ("地图 " + id) : name);
        row.put("parent", mapParent(id, name));
        row.put("startX", rs.getInt("startX"));
        row.put("endX", rs.getInt("endX"));
        row.put("startY", rs.getInt("startY"));
        row.put("endY", rs.getInt("endY"));
        return row;
    }

    private static boolean nameHas(String name, String... keys) {
        for (String key : keys) {
            if (name.contains(key)) return true;
        }
        return false;
    }

    private static boolean hasFloor(String name) {
        for (int floor = 1; floor <= 9; floor++) {
            if (name.contains(floor + "楼") || name.contains(floor + "樓")) return true;
        }
        return false;
    }

    private static String mapParent(int id, String name) {
        if (name == null) name = "";
        boolean sea = nameHas(name, "伊娃", "湖", "船", "海底", "海贼", "航线");
        boolean island = name.contains("岛") || name.contains("島");
        boolean dungeon = hasFloor(name) || nameHas(name,
                "领域", "領域", "地下", "要塞", "空间", "空間", "底比斯", "提卡尔", "提卡爾",
                "洞穴", "洞窟", "地监", "地監", "栖息地", "棲息地");
        boolean land = nameHas(name,
                "威顿", "威頓", "亚丁", "亞丁", "古鲁丁", "古魯丁", "欧瑞", "歐瑞",
                "奇岩", "肯特", "风木", "風木", "海音", "燃柳", "火田",
                "银骑士", "銀騎士", "抵抗军", "抵抗軍", "隐遁", "隱遁", "商店村",
                "说话", "說話", "歌唱", "隐藏之谷", "隱藏之谷",
                "贝希摩斯", "貝希摩斯", "希培莉亚", "希培莉亞",
                "盟屋", "旅馆", "旅館", "小屋", "村庄", "村莊", "村", "城", "大陆", "大陸");
        if (sea) return "海域与航线";
        if (island) return "岛屿地区";
        if (dungeon) return "地下与副本";
        if (land || id == 4) return "亚丁大陆";
        return "未分类";
    }

    Map<String, Object> itemMeta() throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            result.put("etcTypes", distinct(statement, "SELECT DISTINCT item_type FROM etcitem ORDER BY item_type"));
            result.put("useTypes", distinct(statement, "SELECT DISTINCT use_type FROM etcitem ORDER BY use_type"));
            result.put("weaponTypes", distinct(statement, "SELECT DISTINCT type FROM weapon ORDER BY type"));
            result.put("armorTypes", distinct(statement, "SELECT DISTINCT type FROM armor ORDER BY type"));
            result.put("materials", distinct(statement,
                    "SELECT DISTINCT material FROM ("
                            + "SELECT material FROM etcitem UNION SELECT material FROM weapon UNION SELECT material FROM armor"
                            + ") t ORDER BY material"));
            result.put("classnames", distinct(statement,
                    "SELECT DISTINCT classname FROM etcitem WHERE classname IS NOT NULL AND classname<>'' ORDER BY classname"));
            result.put("nextEtcId", scalarInt(statement, "SELECT IFNULL(MAX(item_id),40000)+1 FROM etcitem"));
            result.put("nextWeaponId", scalarInt(statement, "SELECT IFNULL(MAX(item_id),1)+1 FROM weapon"));
            result.put("nextArmorId", scalarInt(statement, "SELECT IFNULL(MAX(item_id),20000)+1 FROM armor"));
        }
        return result;
    }

    Map<String, Object> createItem(Map<?, ?> body) throws SQLException {
        String table = stringValue(body.get("table"));
        if (!"etcitem".equals(table) && !"weapon".equals(table) && !"armor".equals(table)) {
            throw new IllegalArgumentException("物品必须落在 etcitem / weapon / armor");
        }
        int requested = intValue(body.get("itemId"), 0);
        String name = requiredText(body.get("name"), "名称");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                int templateId = findTemplate(connection, table, stringValue(body.get("type")));
                int newId = requested > 0 ? requested : nextId(connection, table);
                if (existsItem(connection, newId)) {
                    throw new IllegalArgumentException("物品编号 " + newId + " 已存在");
                }
                cloneRow(connection, table, "item_id", templateId, newId);
                applyItemOverrides(connection, table, newId, body, name);
                connection.commit();
                itemCache = null;
                audit.write("新建物品", table + " / " + newId + " / " + name);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("itemId", newId);
                result.put("table", table);
                result.put("name", name);
                result.put("needReload", true);
                return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    List<Map<String, Object>> npcs(String query, int offset, int limit, String kind) throws SQLException {
        List<Integer> descIds = clientStrings.idsMatching(simplify(query));
        StringBuilder sql = new StringBuilder("SELECT n.npcid,n.name,n.nameid,n.impl,n.gfxid,n.classname,"
                + "(SELECT COUNT(*) FROM shop s WHERE s.npc_id=n.npcid) shops,"
                + "(CASE WHEN n.impl='L1Monster' THEN (SELECT COUNT(*) FROM spawnlist s WHERE s.npc_templateid=n.npcid)"
                + " ELSE (SELECT COUNT(*) FROM spawnlist_npc sp WHERE sp.npc_templateid=n.npcid) END) spawns "
                + "FROM npc n WHERE (CAST(n.npcid AS CHAR) LIKE ? OR n.name LIKE ? OR n.nameid LIKE ? "
                + "OR CAST(n.gfxid AS CHAR) LIKE ?");
        for (int i = 0; i < descIds.size(); i++) sql.append(" OR n.nameid=?");
        sql.append(")");
        appendNpcKind(sql, kind);
        sql.append(" ORDER BY n.npcid LIMIT ? OFFSET ?");
        String pattern = "%" + simplify(query) + "%";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            statement.setString(i++, pattern);
            statement.setString(i++, pattern);
            statement.setString(i++, pattern);
            statement.setString(i++, pattern);
            for (Integer id : descIds) statement.setString(i++, "$" + id);
            statement.setInt(i++, Math.max(1, Math.min(limit, 200)));
            statement.setInt(i, Math.max(0, offset));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("npcid"));
                    row.put("name", clientStrings.resolve(rs.getString("nameid"), rs.getString("name")));
                    row.put("nameid", simplify(rs.getString("nameid")));
                    row.put("impl", simplify(rs.getString("impl")));
                    row.put("gfxid", rs.getInt("gfxid"));
                    row.put("classname", simplify(rs.getString("classname")));
                    row.put("shops", rs.getInt("shops"));
                    row.put("spawns", rs.getInt("spawns"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    int countNpcs(String query, String kind) throws SQLException {
        List<Integer> descIds = clientStrings.idsMatching(simplify(query));
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM npc WHERE (CAST(npcid AS CHAR) LIKE ? OR name LIKE ? OR nameid LIKE ? OR CAST(gfxid AS CHAR) LIKE ?");
        for (int i = 0; i < descIds.size(); i++) sql.append(" OR nameid=?");
        sql.append(")");
        appendNpcKind(sql, kind);
        String pattern = "%" + simplify(query) + "%";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            statement.setString(i++, pattern);
            statement.setString(i++, pattern);
            statement.setString(i++, pattern);
            statement.setString(i++, pattern);
            for (Integer id : descIds) statement.setString(i++, "$" + id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    Map<String, Object> npcMeta() throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            result.put("impls", distinct(statement, "SELECT DISTINCT impl FROM npc ORDER BY impl"));
            result.put("gfxids", ints(statement, "SELECT DISTINCT gfxid FROM npc ORDER BY gfxid"));
            result.put("nextNpcId", scalarInt(statement, "SELECT IFNULL(MAX(npcid),90000)+1 FROM npc WHERE npcid < 100000"));
        }
        return result;
    }

    private static void appendNpcKind(StringBuilder sql, String kind) {
        if ("monster".equals(kind)) {
            sql.append(" AND impl='L1Monster'");
        } else if ("npc".equals(kind)) {
            sql.append(" AND impl<>'L1Monster'");
        }
    }

    Map<String, Object> createNpc(Map<?, ?> body) throws SQLException {
        String name = requiredText(body.get("name"), "名称");
        String nameid = stringValue(body.get("nameid"));
        if (nameid.isEmpty()) nameid = name;
        String impl = stringValue(body.get("impl"));
        if (impl.isEmpty()) impl = "L1Merchant";
        int gfxid = intValue(body.get("gfxid"), 0);
        if (gfxid <= 0) throw new IllegalArgumentException("必须选择形象 gfxid");
        int requested = intValue(body.get("npcId"), 0);
        boolean merchant = impl.contains("Merchant") || Boolean.TRUE.equals(body.get("merchant"));
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                int templateId = merchant ? 90043 : findNpcTemplate(connection, impl);
                int newId = requested > 0 ? requested : nextNpcId(connection);
                if (existsNpc(connection, newId)) {
                    throw new IllegalArgumentException("NPC 编号 " + newId + " 已存在");
                }
                cloneRow(connection, "npc", "npcid", templateId, newId);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE npc SET name=?,nameid=?,impl=?,gfxid=? WHERE npcid=?")) {
                    update.setString(1, name);
                    update.setString(2, nameid);
                    update.setString(3, impl);
                    update.setInt(4, gfxid);
                    update.setInt(5, newId);
                    update.executeUpdate();
                }
                boolean wroteNpcaction = false;
                if (merchant) {
                    wroteNpcaction = !npcActionExists(connection, newId);
                    ensureNpcAction(connection, newId, "pcShop");
                }
                connection.commit();
                audit.write("新建NPC", newId + " / " + name + " / gfx=" + gfxid + " / " + impl);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("npcId", newId);
                result.put("name", name);
                result.put("wroteNpcaction", wroteNpcaction);
                result.put("needReload", true);
                return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    List<Map<String, Object>> shop(int npcId) throws SQLException {
        String sql = "SELECT s.item_id,s.order_id,s.selling_price,s.purchasing_price,s.pack_count "
                + "FROM shop s WHERE s.npc_id=? ORDER BY s.order_id,s.item_id";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, npcId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    Item item = items().stream().filter(it -> it.id == itemId).findFirst().orElse(null);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("itemId", itemId);
                    row.put("name", item == null ? ("#" + itemId) : item.name);
                    row.put("invgfx", item == null ? 0 : item.invgfx);
                    row.put("orderId", rs.getInt("order_id"));
                    row.put("selling", rs.getInt("selling_price"));
                    row.put("purchasing", rs.getInt("purchasing_price"));
                    row.put("pack", rs.getInt("pack_count"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    void saveShop(int npcId, List<Map<String, Object>> items, boolean copyFrom) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!existsNpc(connection, npcId)) {
                    throw new IllegalArgumentException("NPC 不存在");
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM shop WHERE npc_id=?")) {
                    delete.setInt(1, npcId);
                    delete.executeUpdate();
                }
                int order = 0;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO shop (npc_id,item_id,order_id,selling_price,pack_count,purchasing_price) VALUES (?,?,?,?,?,?)")) {
                    for (Map<String, Object> item : items) {
                        insert.setInt(1, npcId);
                        insert.setInt(2, intValue(item.get("itemId"), 0));
                        insert.setInt(3, intValue(item.get("orderId"), order));
                        insert.setInt(4, intValue(item.get("selling"), -1));
                        insert.setInt(5, intValue(item.get("pack"), 0));
                        insert.setInt(6, intValue(item.get("purchasing"), -1));
                        insert.addBatch();
                        order++;
                    }
                    insert.executeBatch();
                }
                ensureNpcAction(connection, npcId, "pcShop");
                connection.commit();
                audit.write("货单", npcId + " / " + items.size() + " 项" + (copyFrom ? " / 复制" : ""));
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void copyShop(int fromNpc, int toNpc) throws SQLException {
        List<Map<String, Object>> rows = shop(fromNpc);
        if (rows.isEmpty()) throw new IllegalArgumentException("来源货单为空");
        saveShop(toNpc, rows, true);
    }

    String npcImpl(int npcId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT impl FROM npc WHERE npcid=?")) {
            statement.setInt(1, npcId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("NPC 模板不存在");
                return simplify(rs.getString("impl"));
            }
        }
    }

    boolean isMonster(int npcId) throws SQLException {
        return "L1Monster".equals(npcImpl(npcId));
    }

    Map<String, Object> spawnAt(int npcId, int x, int y, int map, int heading, String location) throws SQLException {
        boolean monster = isMonster(npcId);
        Map<String, Object> result = monster
                ? spawnMob(npcId, x, y, map, heading, location)
                : spawnNpc(npcId, x, y, map, heading, location);
        result.put("table", monster ? "spawnlist" : "spawnlist_npc");
        result.put("monster", monster);
        return result;
    }

    Map<String, Object> spawnNpc(int npcId, int x, int y, int map, int heading, String location) throws SQLException {
        heading = heading & 7;
        try (Connection connection = open()) {
            if (!existsNpc(connection, npcId)) throw new IllegalArgumentException("NPC 模板不存在");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO spawnlist_npc (location,count,npc_templateid,locx,locy,randomx,randomy,heading,respawn_delay,mapid,movement_distance) "
                            + "VALUES (?,?,?,?,?,0,0,?,60,?,0)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, location == null || location.isEmpty() ? "gmtool" : location);
                insert.setInt(2, 1);
                insert.setInt(3, npcId);
                insert.setInt(4, x);
                insert.setInt(5, y);
                insert.setInt(6, heading);
                insert.setInt(7, map);
                insert.executeUpdate();
                int spawnId = 0;
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) spawnId = keys.getInt(1);
                }
                audit.write("刷新NPC", npcId + " @ " + map + ":" + x + "," + y + " h=" + heading);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("spawnId", spawnId);
                result.put("needReload", true);
                result.put("table", "spawnlist_npc");
                return result;
            }
        }
    }

    Map<String, Object> spawnMob(int npcId, int x, int y, int map, int heading, String location) throws SQLException {
        heading = heading & 7;
        try (Connection connection = open()) {
            if (!existsNpc(connection, npcId)) throw new IllegalArgumentException("NPC 模板不存在");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO spawnlist (location,count,npc_templateid,group_id,locx,locy,randomx,randomy,"
                            + "locx1,locy1,locx2,locy2,heading,min_respawn_delay,max_respawn_delay,mapid,"
                            + "respawn_screen,movement_distance,rest,near_spawn) "
                            + "VALUES (?,?,?,0,?,?,0,0,0,0,0,0,?,30,30,?,0,0,0,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, location == null || location.isEmpty() ? "gmtool" : location);
                insert.setInt(2, 1);
                insert.setInt(3, npcId);
                insert.setInt(4, x);
                insert.setInt(5, y);
                insert.setInt(6, heading);
                insert.setInt(7, map);
                insert.executeUpdate();
                int spawnId = 0;
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) spawnId = keys.getInt(1);
                }
                audit.write("刷怪点", npcId + " @ " + map + ":" + x + "," + y + " h=" + heading);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("spawnId", spawnId);
                result.put("needReload", true);
                result.put("table", "spawnlist");
                return result;
            }
        }
    }

    List<Map<String, Object>> spawnCounts(int npcId, String table) throws SQLException {
        String t = spawnTable(table);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id,count FROM " + t + " WHERE npc_templateid=?")) {
            statement.setInt(1, npcId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("count", rs.getInt("count"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    List<Map<String, Object>> disableSpawns(int npcId) throws SQLException {
        String table = isMonster(npcId) ? "spawnlist" : "spawnlist_npc";
        List<Map<String, Object>> previous = spawnCounts(npcId, table);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET count=0 WHERE npc_templateid=?")) {
            statement.setInt(1, npcId);
            statement.executeUpdate();
            audit.write("禁用刷新", table + " npc=" + npcId);
        }
        for (Map<String, Object> row : previous) row.put("table", table);
        return previous;
    }

    void restoreSpawnCounts(List<Map<String, Object>> previous) throws SQLException {
        if (previous == null || previous.isEmpty()) return;
        String table = spawnTable(stringValue(previous.get(0).get("table")));
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET count=? WHERE id=?")) {
            for (Map<String, Object> row : previous) {
                statement.setInt(1, intValue(row.get("count"), 0));
                statement.setInt(2, intValue(row.get("id"), 0));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        audit.write("恢复刷新", previous.size() + " 点");
    }

    void deleteSpawn(int spawnId) throws SQLException {
        deleteSpawn(spawnId, "spawnlist_npc");
    }

    void deleteSpawn(int spawnId, String table) throws SQLException {
        if (spawnId <= 0) throw new IllegalArgumentException("缺少 spawnId");
        String t = spawnTable(table);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + t + " WHERE id=?")) {
            statement.setInt(1, spawnId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalStateException("刷新点 " + spawnId + " 已不存在");
            }
            audit.write("删除刷新点", t + " " + spawnId);
        }
    }

    private static String spawnTable(String table) {
        if ("spawnlist".equals(table)) return "spawnlist";
        return "spawnlist_npc";
    }

    void undoCreateNpc(int npcId, boolean wroteNpcaction) throws SQLException {
        if (npcId < 90000 || npcId > 99999) {
            throw new IllegalArgumentException("只能撤回本次新建的 90xxx NPC");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (!existsNpc(connection, npcId)) {
                    throw new IllegalStateException("NPC " + npcId + " 已不存在");
                }
                if (wroteNpcaction) {
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM npcaction WHERE npcid=?")) {
                        del.setInt(1, npcId);
                        del.executeUpdate();
                    }
                }
                try (PreparedStatement del = connection.prepareStatement("DELETE FROM shop WHERE npc_id=?")) {
                    del.setInt(1, npcId);
                    del.executeUpdate();
                }
                try (PreparedStatement del = connection.prepareStatement("DELETE FROM npc WHERE npcid=?")) {
                    del.setInt(1, npcId);
                    del.executeUpdate();
                }
                connection.commit();
                audit.write("撤回新建NPC", String.valueOf(npcId));
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    boolean npcActionExists(int npcId) throws SQLException {
        try (Connection connection = open()) {
            return npcActionExists(connection, npcId);
        }
    }

    private Map<String, Object> characterRow(ResultSet rs) throws SQLException {
        int type = rs.getInt("Type");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getInt("objid"));
        row.put("name", simplify(rs.getString("char_name")));
        row.put("account", rs.getString("account_name"));
        row.put("type", type);
        row.put("className", type >= 0 && type < CLASS_NAMES.length ? CLASS_NAMES[type] : ("职业" + type));
        row.put("level", rs.getInt("level"));
        row.put("exp", rs.getLong("Exp"));
        row.put("maxHp", rs.getInt("MaxHp"));
        row.put("maxMp", rs.getInt("MaxMp"));
        row.put("hp", rs.getInt("CurHp"));
        row.put("mp", rs.getInt("CurMp"));
        row.put("access", rs.getInt("AccessLevel"));
        row.put("online", rs.getInt("OnlineStatus") != 0);
        row.put("map", rs.getInt("MapID"));
        row.put("x", rs.getInt("LocX"));
        row.put("y", rs.getInt("LocY"));
        row.put("heading", rs.getInt("Heading"));
        row.put("accountAccess", rs.getInt("account_access"));
        row.put("banned", rs.getInt("banned") != 0);
        return row;
    }

    private List<Item> items() throws SQLException {
        List<Item> cached = itemCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (itemCache == null) itemCache = loadItems();
            return itemCache;
        }
    }

    private List<Item> loadItems() throws SQLException {
        String sql = "SELECT item_id,name,name_id,'weapon' category,type,material,weight,0 stackable,invgfx,classname FROM weapon "
                + "UNION ALL SELECT item_id,name,name_id,'armor',type,material,weight,0,invgfx,classname FROM armor "
                + "UNION ALL SELECT item_id,name,name_id,'etc',item_type,material,weight,stackable,invgfx,classname FROM etcitem";
        List<Item> result = new ArrayList<>();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String original = rs.getString("name");
                String name = simplify(original == null ? "未命名物品" : original);
                result.add(new Item(rs.getInt("item_id"), name, simplify(rs.getString("name_id")),
                        rs.getString("category"), rs.getString("type"), rs.getString("material"),
                        rs.getInt("weight"), rs.getInt("stackable") != 0, rs.getInt("invgfx"),
                        simplify(rs.getString("classname"))));
            }
        }
        result.sort(Comparator.comparingInt(item -> item.id));
        return result;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, login, password);
    }

    private static int scalarInt(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> distinct(Statement statement, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null && !value.isEmpty()) result.add(value);
            }
        }
        return result;
    }

    private static List<Integer> ints(Statement statement, String sql) throws SQLException {
        List<Integer> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) result.add(rs.getInt(1));
        }
        return result;
    }

    private static String characterOrder(String sort) {
        if (sort == null) sort = "";
        boolean desc = sort.startsWith("-");
        String key = desc ? sort.substring(1) : sort;
        String column;
        switch (key) {
            case "exp": column = "c.Exp"; break;
            case "level": column = "c.level"; break;
            case "maxhp": column = "c.MaxHp"; break;
            case "maxmp": column = "c.MaxMp"; break;
            case "name": column = "c.char_name"; break;
            default:
                return "c.OnlineStatus DESC,c.Exp DESC,c.char_name";
        }
        return column + (desc ? " DESC" : " ASC") + ",c.char_name";
    }

    private static int findTemplate(Connection connection, String table, String type) throws SQLException {
        if (type != null && !type.isEmpty()) {
            String column = "etcitem".equals(table) ? "item_type" : "type";
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT item_id FROM " + table + " WHERE " + column + "=? ORDER BY item_id LIMIT 1")) {
                statement.setString(1, type);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT item_id FROM " + table + " ORDER BY item_id LIMIT 1")) {
            if (!rs.next()) throw new IllegalStateException(table + " 没有任何模板可复制");
            return rs.getInt(1);
        }
    }

    private static int findNpcTemplate(Connection connection, String impl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT npcid FROM npc WHERE impl=? ORDER BY npcid LIMIT 1")) {
            statement.setString(1, impl);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 70030;
    }

    private static int nextId(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT IFNULL(MAX(item_id),1)+1 FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int nextNpcId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT IFNULL(MAX(npcid),90000)+1 FROM npc WHERE npcid BETWEEN 90000 AND 99999")) {
            rs.next();
            int id = rs.getInt(1);
            return id < 90000 ? 90044 : id;
        }
    }

    private static boolean existsItem(Connection connection, int id) throws SQLException {
        String sql = "SELECT 1 FROM (SELECT item_id FROM etcitem UNION SELECT item_id FROM weapon UNION SELECT item_id FROM armor) t WHERE item_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean existsNpc(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM npc WHERE npcid=?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void cloneRow(Connection connection, String table, String pk, int fromId, int toId) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (!pk.equalsIgnoreCase(name)) columns.add(name);
            }
        }
        if (columns.isEmpty()) throw new IllegalStateException("无法读取表结构 " + table);
        String joined = String.join(",", columns);
        String sql = "INSERT INTO " + table + " (" + pk + "," + joined + ") SELECT ?," + joined
                + " FROM " + table + " WHERE " + pk + "=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, toId);
            statement.setInt(2, fromId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("复制模板失败");
            }
        }
    }

    private static void applyItemOverrides(Connection connection, String table, int id, Map<?, ?> body, String name)
            throws SQLException {
        String typeColumn = "etcitem".equals(table) ? "item_type" : "type";
        StringBuilder sql = new StringBuilder("UPDATE " + table + " SET name=?,name_id=?,weight=?,invgfx=?,grdgfx=?,"
                + "classname=?,material=?,bless=?,trade=?,cant_delete=?," + typeColumn + "=?");
        if ("etcitem".equals(table)) {
            sql.append(",use_type=?,stackable=?");
        }
        if ("weapon".equals(table) || "armor".equals(table)) {
            sql.append(",use_royal=?,use_knight=?,use_mage=?,use_elf=?,use_darkelf=?,use_dragonknight=?,use_illusionist=?");
        }
        if ("armor".equals(table)) sql.append(",ac=?");
        sql.append(" WHERE item_id=?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            statement.setString(i++, name);
            statement.setString(i++, firstNonBlank(stringValue(body.get("nameId")), name));
            statement.setInt(i++, intValue(body.get("weight"), 0));
            int gfx = intValue(body.get("invgfx"), 0);
            statement.setInt(i++, gfx);
            statement.setInt(i++, intValue(body.get("grdgfx"), gfx));
            statement.setString(i++, firstNonBlank(stringValue(body.get("classname")), "0"));
            statement.setString(i++, firstNonBlank(stringValue(body.get("material")), "none"));
            statement.setInt(i++, intValue(body.get("bless"), 1));
            statement.setInt(i++, boolInt(body.get("trade")));
            statement.setInt(i++, boolInt(body.get("cantDelete")));
            statement.setString(i++, firstNonBlank(stringValue(body.get("type")), defaultType(table)));
            if ("etcitem".equals(table)) {
                statement.setString(i++, firstNonBlank(stringValue(body.get("useType")), "none"));
                statement.setInt(i++, boolInt(body.get("stackable")));
            }
            if ("weapon".equals(table) || "armor".equals(table)) {
                statement.setInt(i++, boolInt(body.get("useRoyal"), 1));
                statement.setInt(i++, boolInt(body.get("useKnight"), 1));
                statement.setInt(i++, boolInt(body.get("useMage"), 1));
                statement.setInt(i++, boolInt(body.get("useElf"), 1));
                statement.setInt(i++, boolInt(body.get("useDarkelf"), 1));
                statement.setInt(i++, boolInt(body.get("useDragonknight"), 1));
                statement.setInt(i++, boolInt(body.get("useIllusionist"), 1));
            }
            if ("armor".equals(table)) {
                statement.setInt(i++, intValue(body.get("ac"), 0));
            }
            statement.setInt(i, id);
            statement.executeUpdate();
        }
    }

    private static boolean npcActionExists(Connection connection, int npcId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM npcaction WHERE npcid=?")) {
            statement.setInt(1, npcId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureNpcAction(Connection connection, int npcId, String html) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npcaction (npcid,normal_action,caotic_action,teleport_url,teleport_urla) VALUES (?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE normal_action=VALUES(normal_action),caotic_action=VALUES(caotic_action)")) {
            statement.setInt(1, npcId);
            statement.setString(2, html);
            statement.setString(3, html);
            statement.setString(4, "");
            statement.setString(5, "");
            statement.executeUpdate();
        }
    }

    private static String defaultType(String table) {
        if ("weapon".equals(table)) return "sword";
        if ("armor".equals(table)) return "armor";
        return "other";
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("服务端配置缺少 " + key);
        return value;
    }

    private static String normalizeUrl(String jdbcUrl) {
        StringBuilder result = new StringBuilder(jdbcUrl);
        result.append(jdbcUrl.contains("?") ? '&' : '?');
        result.append("useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&characterEncoding=UTF-8");
        return result.toString();
    }

    private static String simplify(String value) {
        return value == null ? "" : value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String requiredText(Object value, String label) {
        String text = stringValue(value);
        if (text.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        return text;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null || "".equals(value)) return fallback;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int boolInt(Object value) {
        return boolInt(value, 0);
    }

    private static int boolInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? 1 : 0;
        if (value instanceof Number) return ((Number) value).intValue() != 0 ? 1 : 0;
        String text = value.toString();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return 1;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return 0;
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Item {
        final int id;
        final String name;
        final String nameId;
        final String category;
        final String type;
        final String material;
        final int weight;
        final boolean stackable;
        final int invgfx;
        final String classname;
        final String searchText;

        Item(int id, String name, String nameId, String category, String type, String material,
             int weight, boolean stackable, int invgfx, String classname) {
            this.id = id;
            this.name = name;
            this.nameId = nameId;
            this.category = category;
            this.type = type == null ? "" : type;
            this.material = material == null ? "" : material;
            this.weight = weight;
            this.stackable = stackable;
            this.invgfx = invgfx;
            this.classname = classname == null ? "" : classname;
            this.searchText = (name + nameId + this.type + this.material + this.classname)
                    .toLowerCase(Locale.ROOT).replace(" ", "");
        }
    }
}
