package com.deltaforce.agentweb.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DataCenterService {
    private final JdbcTemplate jdbc;

    public DataCenterService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> accounts(String userId) {
        return jdbc.query(
                "SELECT * FROM df_game_account WHERE owner_user_id = ? ORDER BY is_active DESC, updated_at DESC",
                accountMapper(), userId
        );
    }

    @Transactional
    public Map<String, Object> selectAccount(String userId, String accountId) {
        Map<String, Object> account = requireAccount(userId, accountId);
        jdbc.update("UPDATE df_game_account SET is_active = FALSE WHERE owner_user_id = ?", userId);
        jdbc.update("UPDATE df_game_account SET is_active = TRUE, updated_at = ? WHERE owner_user_id = ? AND id = ?",
                now(), userId, accountId);
        account.put("active", true);
        return account;
    }

    @Transactional
    public Map<String, Object> deleteAccount(String userId, String accountId) {
        Map<String, Object> account = requireAccount(userId, accountId);
        boolean active = Boolean.TRUE.equals(account.get("active"));
        jdbc.update("DELETE FROM df_game_account WHERE owner_user_id = ? AND id = ?", userId, accountId);
        if (active) {
            List<Map<String, Object>> remaining = accounts(userId);
            if (!remaining.isEmpty()) selectAccount(userId, String.valueOf(remaining.get(0).get("id")));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deleted", true);
        result.put("accountId", accountId);
        return result;
    }

    @Transactional
    public Map<String, Object> importData(String userId, Map<String, Object> payload) {
        if (payload == null) throw badRequest("导入内容不能为空");
        Map<String, Object> accountInput = mapValue(first(payload, "account", "gameAccount"));
        if (accountInput.isEmpty()) accountInput = payload;
        String gamePlayerId = text(accountInput, "playerId", "player_id", "gamePlayerId", "game_player_id");
        String playerName = text(accountInput, "playerName", "player_name", "name");
        if (!StringUtils.hasText(gamePlayerId)) throw badRequest("account.playerId 不能为空");
        if (!StringUtils.hasText(playerName)) playerName = gamePlayerId;

        String accountId = upsertAccount(userId, gamePlayerId, playerName,
                text(accountInput, "playerIcon", "player_icon", "icon"),
                intValue(accountInput, 0, "level", "levelValue"),
                intValue(accountInput, 36, "area", "areaValue"));

        List<Object> battles = listValue(first(payload, "battles", "records", "data"));
        int inserted = 0;
        int updated = 0;
        int players = 0;
        int items = 0;
        for (Object raw : battles) {
            Map<String, Object> battle = mapValue(raw);
            if (battle.isEmpty()) continue;
            String roomId = text(battle, "roomId", "room_id");
            if (!StringUtils.hasText(roomId)) continue;
            String existingId = findBattleId(userId, accountId, roomId);
            boolean exists = StringUtils.hasText(existingId);
            String battleId = exists ? existingId : UUID.randomUUID().toString();
            saveBattle(userId, accountId, battleId, roomId, battle, exists);
            jdbc.update("DELETE FROM df_battle_player WHERE owner_user_id = ? AND battle_id = ?", userId, battleId);
            jdbc.update("DELETE FROM df_battle_item WHERE owner_user_id = ? AND battle_id = ?", userId, battleId);

            List<Object> playerRows = listValue(first(battle, "players", "teammates", "battlePlayers", "battle_players"));
            if (playerRows.isEmpty()) {
                Map<String, Object> self = mapValue(first(battle, "self", "selfPlayer"));
                if (!self.isEmpty()) playerRows = Collections.<Object>singletonList(self);
            }
            for (Object playerRaw : playerRows) {
                Map<String, Object> player = mapValue(playerRaw);
                if (player.isEmpty()) continue;
                savePlayer(userId, battleId, gamePlayerId, player);
                players++;
            }
            List<Object> itemRows = listValue(first(battle, "items", "battleItems", "battle_items"));
            for (Object itemRaw : itemRows) {
                Map<String, Object> item = mapValue(itemRaw);
                if (item.isEmpty()) continue;
                saveItem(userId, battleId, item);
                items++;
            }
            if (exists) updated++; else inserted++;
        }
        selectAccount(userId, accountId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountId", accountId);
        result.put("gamePlayerId", gamePlayerId);
        result.put("playerName", playerName);
        result.put("insertedBattles", inserted);
        result.put("updatedBattles", updated);
        result.put("players", players);
        result.put("items", items);
        return result;
    }

    @Transactional
    public Map<String, Object> importLegacyBackup(String userId, byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) throw badRequest("备份文件内容为空");
        if (zipBytes.length > 100 * 1024 * 1024) throw badRequest("备份文件不能超过 100MB");
        return importLegacyBackup(userId, new ByteArrayInputStream(zipBytes));
    }

    @Transactional
    public Map<String, Object> importLegacyBackup(String userId, InputStream zipStream) {
        if (zipStream == null) throw badRequest("备份文件内容为空");
        Path sqliteFile = null;
        try {
            sqliteFile = Files.createTempFile("delta-force-import-", ".sqlite3");
            boolean found = false;
            try (ZipInputStream zip = new ZipInputStream(zipStream)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && "database.sqlite3".equals(entry.getName())) {
                        byte[] chunk = new byte[8192];
                        int read;
                        int total = 0;
                        try (OutputStream output = Files.newOutputStream(sqliteFile)) {
                            while ((read = zip.read(chunk)) >= 0) {
                                total += read;
                                if (total > 250 * 1024 * 1024) throw badRequest("备份数据库解压后不能超过 250MB");
                                output.write(chunk, 0, read);
                            }
                        }
                        found = true;
                        break;
                    }
                }
            }
            if (!found) throw badRequest("不是有效的数据中心备份：缺少 database.sqlite3");
            Class.forName("org.sqlite.JDBC");
            int accountsImported = 0;
            int insertedBattles = 0;
            int updatedBattles = 0;
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
                List<Map<String, Object>> accounts = readLegacyAccounts(source);
                if (accounts.isEmpty()) throw badRequest("备份中没有游戏账号");
                for (Map<String, Object> account : accounts) {
                    String playerId = String.valueOf(account.get("playerId"));
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("account", account);
                    payload.put("battles", readLegacyBattles(source, playerId));
                    Map<String, Object> imported = importData(userId, payload);
                    accountsImported++;
                    insertedBattles += number(imported.get("insertedBattles")).intValue();
                    updatedBattles += number(imported.get("updatedBattles")).intValue();
                }
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("accountsImported", accountsImported);
            result.put("insertedBattles", insertedBattles);
            result.put("updatedBattles", updatedBattles);
            result.put("format", "delta-force-data-center-backup-v1");
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw badRequest("备份解析失败：" + ex.getMessage());
        } finally {
            if (sqliteFile != null) {
                try { Files.deleteIfExists(sqliteFile); } catch (IOException ignored) { }
            }
        }
    }

    private List<Map<String, Object>> readLegacyAccounts(Connection source) throws SQLException {
        List<Map<String, Object>> accounts = new ArrayList<Map<String, Object>>();
        try (PreparedStatement statement = source.prepareStatement(
                "SELECT player_id, player_name, player_icon, level, area FROM Account ORDER BY is_active DESC, last_login DESC")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> account = new LinkedHashMap<String, Object>();
                    account.put("playerId", rs.getString("player_id"));
                    account.put("playerName", rs.getString("player_name"));
                    account.put("playerIcon", rs.getString("player_icon"));
                    account.put("level", rs.getInt("level"));
                    account.put("area", rs.getInt("area"));
                    if (StringUtils.hasText(String.valueOf(account.get("playerId")))) accounts.add(account);
                }
            }
        }
        return accounts;
    }

    private List<Map<String, Object>> readLegacyBattles(Connection source, String playerId) throws SQLException {
        List<Map<String, Object>> battles = new ArrayList<Map<String, Object>>();
        String sql = "SELECT r.*, m.map_name, rl.role_name FROM Record r " +
                "LEFT JOIN MapID m ON m.map_id = r.map_id LEFT JOIN Role rl ON rl.role_id = r.role_id " +
                "WHERE r.player_id = ? ORDER BY r.event_time";
        try (PreparedStatement statement = source.prepareStatement(sql)) {
            statement.setString(1, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> battle = new LinkedHashMap<String, Object>();
                    String roomId = rs.getString("room_id");
                    battle.put("roomId", roomId); battle.put("mapId", rs.getString("map_id")); battle.put("mapName", rs.getString("map_name"));
                    battle.put("eventTime", rs.getString("event_time")); battle.put("startTime", rs.getString("start_time"));
                    battle.put("durationSeconds", rs.getInt("duration_s")); battle.put("gameResult", rs.getInt("game_result"));
                    battle.put("gameRule", rs.getInt("game_rule")); battle.put("roleId", rs.getString("role_id")); battle.put("roleName", rs.getString("role_name"));
                    battle.put("killCount", rs.getInt("kill_cnt")); battle.put("playerKillCount", rs.getInt("kill_player"));
                    battle.put("assistCount", rs.getInt("assist_cnt")); battle.put("rescueCount", rs.getInt("rescue"));
                    battle.put("collectionValue", rs.getLong("collection_price")); battle.put("gainedValue", rs.getLong("gained_price"));
                    battle.put("profitLoss", rs.getLong("profit_loss")); battle.put("equipmentValue", rs.getLong("original_equipment_price"));
                    battle.put("ranked", rs.getInt("is_rank_match") != 0); battle.put("leftEarly", rs.getInt("is_leave") != 0);
                    battle.put("blueBox", rs.getInt("has_blue_box") != 0); battle.put("queue", rs.getString("queue"));
                    battle.put("players", readLegacyPlayers(source, roomId)); battle.put("items", readLegacyItems(source, roomId));
                    battles.add(battle);
                }
            }
        }
        return battles;
    }

    private List<Map<String, Object>> readLegacyPlayers(Connection source, String roomId) throws SQLException {
        List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
        String sql = "SELECT bd.*, rl.role_name FROM BattleDetail bd LEFT JOIN Role rl ON rl.role_id = bd.armed_force_id WHERE bd.room_id = ?";
        try (PreparedStatement statement = source.prepareStatement(sql)) {
            statement.setString(1, roomId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> player = new LinkedHashMap<String, Object>();
                    player.put("playerId", rs.getString("player_id")); player.put("playerName", rs.getString("player_name"));
                    player.put("playerIcon", rs.getString("player_icon")); player.put("teamId", rs.getInt("team_id"));
                    player.put("roleId", rs.getString("armed_force_id")); player.put("roleName", rs.getString("role_name"));
                    player.put("killCount", rs.getInt("kill_cnt")); player.put("playerKillCount", rs.getInt("kill_player"));
                    player.put("assistCount", rs.getInt("assist_cnt")); player.put("rescueCount", rs.getInt("rescue"));
                    player.put("gainedValue", rs.getLong("gained_price")); player.put("profitLoss", rs.getLong("profit_loss"));
                    player.put("isSelf", rs.getInt("is_self") != 0); players.add(player);
                }
            }
        }
        return players;
    }

    private List<Map<String, Object>> readLegacyItems(Connection source, String roomId) throws SQLException {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        try (PreparedStatement statement = source.prepareStatement(
                "SELECT player_id, item_id, item_name, pic, grade, num, price FROM battles_items WHERE room_id = ?")) {
            statement.setString(1, roomId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("playerId", rs.getString("player_id")); item.put("itemId", rs.getString("item_id"));
                    item.put("itemName", rs.getString("item_name")); item.put("pictureUrl", rs.getString("pic"));
                    item.put("grade", rs.getInt("grade")); item.put("quantity", rs.getInt("num")); item.put("unitPrice", rs.getLong("price"));
                    items.add(item);
                }
            }
        }
        return items;
    }

    public Map<String, Object> summary(String userId, String requestedAccountId) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        Map<String, Object> result = emptySummary();
        result.put("accounts", accounts(userId));
        if (account == null) return result;
        String accountId = String.valueOf(account.get("id"));
        Map<String, Object> stats = jdbc.queryForObject(
                "SELECT COUNT(*) total, " +
                        "COALESCE(SUM(CASE WHEN game_result = 0 THEN 1 ELSE 0 END), 0) escaped, " +
                        "COALESCE(SUM(player_kill_count), 0) kills, COALESCE(SUM(profit_loss), 0) profit, " +
                        "COALESCE(AVG(duration_seconds), 0) avg_duration, COALESCE(AVG(profit_loss), 0) avg_profit " +
                        "FROM df_battle WHERE owner_user_id = ? AND account_id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    long total = rs.getLong("total");
                    long escaped = rs.getLong("escaped");
                    row.put("totalBattles", total);
                    row.put("escapedBattles", escaped);
                    row.put("escapeRate", total == 0 ? 0d : round(escaped * 100d / total));
                    row.put("playerKills", rs.getLong("kills"));
                    row.put("totalProfit", rs.getLong("profit"));
                    row.put("averageDurationSeconds", Math.round(rs.getDouble("avg_duration")));
                    row.put("averageProfit", Math.round(rs.getDouble("avg_profit")));
                    return row;
                }, userId, accountId
        );
        result.putAll(stats);
        result.put("activeAccount", account);
        Long itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM df_battle_item i JOIN df_battle b ON b.id = i.battle_id " +
                        "WHERE i.owner_user_id = ? AND b.owner_user_id = ? AND b.account_id = ?",
                Long.class, userId, userId, accountId);
        result.put("itemCount", itemCount == null ? 0 : itemCount);
        return result;
    }

    public Map<String, Object> records(String userId, String requestedAccountId, int page, int size,
                                       String mapName, String resultValue, String start, String end) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        out.put("page", safePage);
        out.put("size", safeSize);
        if (account == null) {
            out.put("total", 0);
            out.put("data", Collections.emptyList());
            return out;
        }
        StringBuilder where = new StringBuilder(" WHERE owner_user_id = ? AND account_id = ?");
        List<Object> args = new ArrayList<Object>();
        args.add(userId);
        args.add(account.get("id"));
        addFilters(where, args, mapName, resultValue, start, end);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM df_battle" + where, Long.class, args.toArray());
        List<Object> rowsArgs = new ArrayList<Object>(args);
        rowsArgs.add(safeSize);
        rowsArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM df_battle" + where + " ORDER BY event_time DESC LIMIT ? OFFSET ?",
                battleMapper(), rowsArgs.toArray());
        for (Map<String, Object> row : rows) {
            row.put("itemCount", countItems(userId, String.valueOf(row.get("id"))));
        }
        out.put("total", total == null ? 0 : total);
        out.put("data", rows);
        out.put("maps", jdbc.query(
                "SELECT DISTINCT map_name FROM df_battle WHERE owner_user_id = ? AND account_id = ? " +
                        "AND map_name IS NOT NULL AND map_name <> '' ORDER BY map_name",
                (rs, rowNum) -> rs.getString(1), userId, account.get("id")));
        return out;
    }

    public Map<String, Object> battleDetail(String userId, String battleId) {
        Map<String, Object> battle = requireBattle(userId, battleId);
        List<Map<String, Object>> players = jdbc.query(
                "SELECT * FROM df_battle_player WHERE owner_user_id = ? AND battle_id = ? ORDER BY is_self DESC, team_id, player_name",
                playerMapper(), userId, battleId);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT * FROM df_battle_item WHERE owner_user_id = ? AND battle_id = ? ORDER BY unit_price * quantity DESC, item_name",
                itemMapper(), userId, battleId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("battle", battle);
        result.put("players", players);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> analysis(String userId, String requestedAccountId, String start, String end) {
        List<Map<String, Object>> rows = filteredBattles(userId, requestedAccountId, start, end);
        Map<String, Aggregate> maps = new LinkedHashMap<String, Aggregate>();
        Map<String, Aggregate> roles = new LinkedHashMap<String, Aggregate>();
        Map<String, Aggregate> results = new LinkedHashMap<String, Aggregate>();
        for (Map<String, Object> row : rows) {
            addAggregate(maps, label(row.get("mapName"), "未知地图"), row);
            addAggregate(roles, label(row.get("roleName"), "未知干员"), row);
            int gameResult = number(row.get("gameResult")).intValue();
            addAggregate(results, gameResult == 0 ? "成功撤离" : "未撤离", row);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("totalBattles", rows.size());
        out.put("maps", aggregateRows(maps));
        out.put("roles", aggregateRows(roles));
        out.put("results", aggregateRows(results));
        out.put("highlights", highlightRows(rows));
        return out;
    }

    public Map<String, Object> trends(String userId, String requestedAccountId, String bucket, String start, String end) {
        List<Map<String, Object>> rows = filteredBattles(userId, requestedAccountId, start, end);
        String safeBucket = Arrays.asList("day", "week", "month").contains(bucket) ? bucket : "day";
        Map<String, Aggregate> grouped = new LinkedHashMap<String, Aggregate>();
        for (Map<String, Object> row : rows) {
            String key = bucketKey(String.valueOf(row.get("eventTime")), safeBucket);
            addAggregate(grouped, key, row);
        }
        List<Map<String, Object>> points = aggregateRows(grouped);
        Collections.sort(points, Comparator.comparing(item -> String.valueOf(item.get("label"))));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("bucket", safeBucket);
        out.put("data", points);
        return out;
    }

    public Map<String, Object> items(String userId, String requestedAccountId, String start, String end) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (account == null) {
            out.put("data", Collections.emptyList());
            out.put("totalValue", 0);
            return out;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT i.item_id, i.item_name, i.grade, SUM(i.quantity) quantity, " +
                        "SUM(i.quantity * i.unit_price) total_value, MAX(i.unit_price) max_unit_price " +
                        "FROM df_battle_item i JOIN df_battle b ON b.id = i.battle_id " +
                        "WHERE i.owner_user_id = ? AND b.owner_user_id = ? AND b.account_id = ?");
        List<Object> args = new ArrayList<Object>();
        args.add(userId);
        args.add(userId);
        args.add(account.get("id"));
        if (StringUtils.hasText(start)) { sql.append(" AND b.event_time >= ?"); args.add(normalizeBoundary(start, false)); }
        if (StringUtils.hasText(end)) { sql.append(" AND b.event_time <= ?"); args.add(normalizeBoundary(end, true)); }
        sql.append(" GROUP BY i.item_id, i.item_name, i.grade ORDER BY total_value DESC LIMIT 500");
        List<Map<String, Object>> data = jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("itemId", rs.getString("item_id"));
            item.put("itemName", rs.getString("item_name"));
            item.put("grade", rs.getInt("grade"));
            item.put("quantity", rs.getLong("quantity"));
            item.put("totalValue", rs.getLong("total_value"));
            item.put("maxUnitPrice", rs.getLong("max_unit_price"));
            return item;
        }, args.toArray());
        long totalValue = 0;
        for (Map<String, Object> item : data) totalValue += number(item.get("totalValue")).longValue();
        out.put("data", data);
        out.put("totalValue", totalValue);
        return out;
    }

    public Map<String, Object> exportData(String userId, String requestedAccountId) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        if (account == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未绑定游戏账号");
        List<Map<String, Object>> battles = jdbc.query(
                "SELECT * FROM df_battle WHERE owner_user_id = ? AND account_id = ? ORDER BY event_time DESC",
                battleMapper(), userId, account.get("id"));
        for (Map<String, Object> battle : battles) {
            String id = String.valueOf(battle.get("id"));
            battle.put("players", jdbc.query("SELECT * FROM df_battle_player WHERE owner_user_id = ? AND battle_id = ?",
                    playerMapper(), userId, id));
            battle.put("items", jdbc.query("SELECT * FROM df_battle_item WHERE owner_user_id = ? AND battle_id = ?",
                    itemMapper(), userId, id));
            battle.remove("id");
            battle.remove("accountId");
        }
        Map<String, Object> cleanAccount = new LinkedHashMap<String, Object>(account);
        cleanAccount.remove("id");
        cleanAccount.remove("active");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("format", "delta-force-agent-data-center-v1");
        out.put("exportedAt", now());
        out.put("account", cleanAccount);
        out.put("battles", battles);
        return out;
    }

    @Transactional
    public Map<String, Object> clearData(String userId) {
        int deleted = jdbc.update("DELETE FROM df_game_account WHERE owner_user_id = ?", userId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", true);
        out.put("accounts", deleted);
        return out;
    }

    private String upsertAccount(String userId, String gamePlayerId, String playerName, String icon, int level, int area) {
        List<String> ids = jdbc.query("SELECT id FROM df_game_account WHERE owner_user_id = ? AND game_player_id = ?",
                (rs, rowNum) -> rs.getString(1), userId, gamePlayerId);
        String timestamp = now();
        if (!ids.isEmpty()) {
            String id = ids.get(0);
            jdbc.update("UPDATE df_game_account SET player_name = ?, player_icon = ?, level_value = ?, area_value = ?, updated_at = ? " +
                            "WHERE owner_user_id = ? AND id = ?",
                    playerName, icon, level, area, timestamp, userId, id);
            return id;
        }
        String id = UUID.randomUUID().toString();
        boolean firstAccount = accounts(userId).isEmpty();
        jdbc.update("INSERT INTO df_game_account (id, owner_user_id, game_player_id, player_name, player_icon, level_value, area_value, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, gamePlayerId, playerName, icon, level, area, firstAccount, timestamp, timestamp);
        return id;
    }

    private void saveBattle(String userId, String accountId, String id, String roomId, Map<String, Object> b, boolean update) {
        Object[] values = new Object[]{
                text(b, "mapId", "map_id"), text(b, "mapName", "map_name"), normalizeTime(first(b, "eventTime", "event_time")),
                normalizeTime(first(b, "startTime", "start_time")), intValue(b, 0, "durationSeconds", "duration_s", "duration"),
                intValue(b, 0, "gameResult", "game_result", "result"), intValue(b, 0, "gameRule", "game_rule"),
                text(b, "roleId", "role_id"), text(b, "roleName", "role_name"), intValue(b, 0, "killCount", "kill_cnt", "kills"),
                intValue(b, 0, "playerKillCount", "kill_player", "playerKills"), intValue(b, 0, "assistCount", "assist_cnt", "assists"),
                intValue(b, 0, "rescueCount", "rescue", "rescues"), longValue(b, 0, "collectionValue", "collection_price"),
                longValue(b, 0, "gainedValue", "gained_price"), longValue(b, 0, "profitLoss", "profit_loss", "profit"),
                longValue(b, 0, "equipmentValue", "original_equipment_price"), boolValue(b, "ranked", "is_rank_match"),
                boolValue(b, "leftEarly", "is_leave"), boolValue(b, "blueBox", "has_blue_box"), text(b, "queue", "queueName")
        };
        if (update) {
            List<Object> args = new ArrayList<Object>(Arrays.asList(values));
            args.add(now()); args.add(userId); args.add(accountId); args.add(id);
            jdbc.update("UPDATE df_battle SET map_id=?, map_name=?, event_time=?, start_time=?, duration_seconds=?, game_result=?, game_rule=?, " +
                            "role_id=?, role_name=?, kill_count=?, player_kill_count=?, assist_count=?, rescue_count=?, collection_value=?, gained_value=?, " +
                            "profit_loss=?, equipment_value=?, ranked=?, left_early=?, blue_box=?, queue_name=?, fetched_at=? " +
                            "WHERE owner_user_id=? AND account_id=? AND id=?", args.toArray());
        } else {
            List<Object> args = new ArrayList<Object>();
            args.add(id); args.add(userId); args.add(accountId); args.add(roomId); args.addAll(Arrays.asList(values)); args.add(now());
            jdbc.update("INSERT INTO df_battle (id,owner_user_id,account_id,room_id,map_id,map_name,event_time,start_time,duration_seconds,game_result,game_rule," +
                            "role_id,role_name,kill_count,player_kill_count,assist_count,rescue_count,collection_value,gained_value,profit_loss,equipment_value," +
                            "ranked,left_early,blue_box,queue_name,fetched_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", args.toArray());
        }
    }

    private void savePlayer(String userId, String battleId, String selfPlayerId, Map<String, Object> p) {
        String playerId = text(p, "playerId", "player_id", "gamePlayerId");
        boolean isSelf = boolValue(p, "self", "isSelf", "is_self") || selfPlayerId.equals(playerId);
        jdbc.update("INSERT INTO df_battle_player (id,owner_user_id,battle_id,game_player_id,player_name,player_icon,team_id,role_id,role_name," +
                        "kill_count,player_kill_count,assist_count,rescue_count,damage,gained_value,profit_loss,is_self) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, battleId, playerId, text(p, "playerName", "player_name", "name"),
                text(p, "playerIcon", "player_icon", "icon"), intValue(p, 0, "teamId", "team_id"), text(p, "roleId", "role_id", "armedForceId"),
                text(p, "roleName", "role_name"), intValue(p, 0, "killCount", "kill_cnt", "kills"),
                intValue(p, 0, "playerKillCount", "kill_player", "playerKills"), intValue(p, 0, "assistCount", "assist_cnt", "assists"),
                intValue(p, 0, "rescueCount", "rescue", "rescues"), longValue(p, 0, "damage"),
                longValue(p, 0, "gainedValue", "gained_price"), longValue(p, 0, "profitLoss", "profit_loss", "profit"), isSelf);
    }

    private void saveItem(String userId, String battleId, Map<String, Object> item) {
        jdbc.update("INSERT INTO df_battle_item (id,owner_user_id,battle_id,game_player_id,item_id,item_name,picture_url,grade,quantity,unit_price) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, battleId, text(item, "playerId", "player_id", "ownerPlayerId"),
                text(item, "itemId", "item_id"), text(item, "itemName", "item_name", "name"), text(item, "pictureUrl", "pic", "image"),
                intValue(item, 0, "grade", "quality"), Math.max(1, intValue(item, 1, "quantity", "num", "count")),
                longValue(item, 0, "unitPrice", "price", "value"));
    }

    private Map<String, Object> resolveAccount(String userId, String requestedId) {
        if (StringUtils.hasText(requestedId)) return requireAccount(userId, requestedId);
        List<Map<String, Object>> active = jdbc.query(
                "SELECT * FROM df_game_account WHERE owner_user_id = ? ORDER BY is_active DESC, updated_at DESC LIMIT 1",
                accountMapper(), userId);
        return active.isEmpty() ? null : active.get(0);
    }

    private Map<String, Object> requireAccount(String userId, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM df_game_account WHERE owner_user_id = ? AND id = ?", accountMapper(), userId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "游戏账号不存在");
        return rows.get(0);
    }

    private Map<String, Object> requireBattle(String userId, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM df_battle WHERE owner_user_id = ? AND id = ?", battleMapper(), userId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "战绩不存在");
        return rows.get(0);
    }

    private String findBattleId(String userId, String accountId, String roomId) {
        List<String> ids = jdbc.query("SELECT id FROM df_battle WHERE owner_user_id = ? AND account_id = ? AND room_id = ?",
                (rs, rowNum) -> rs.getString(1), userId, accountId, roomId);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private long countItems(String userId, String battleId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM df_battle_item WHERE owner_user_id = ? AND battle_id = ?",
                Long.class, userId, battleId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> filteredBattles(String userId, String accountId, String start, String end) {
        Map<String, Object> account = resolveAccount(userId, accountId);
        if (account == null) return Collections.emptyList();
        StringBuilder sql = new StringBuilder("SELECT * FROM df_battle WHERE owner_user_id = ? AND account_id = ?");
        List<Object> args = new ArrayList<Object>();
        args.add(userId); args.add(account.get("id"));
        if (StringUtils.hasText(start)) { sql.append(" AND event_time >= ?"); args.add(normalizeBoundary(start, false)); }
        if (StringUtils.hasText(end)) { sql.append(" AND event_time <= ?"); args.add(normalizeBoundary(end, true)); }
        sql.append(" ORDER BY event_time ASC");
        return jdbc.query(sql.toString(), battleMapper(), args.toArray());
    }

    private void addFilters(StringBuilder where, List<Object> args, String mapName, String resultValue, String start, String end) {
        if (StringUtils.hasText(mapName)) { where.append(" AND map_name = ?"); args.add(mapName); }
        if (StringUtils.hasText(resultValue)) {
            int parsed;
            try { parsed = Integer.parseInt(resultValue); } catch (NumberFormatException ex) { throw badRequest("result 参数无效"); }
            where.append(parsed == 0 ? " AND game_result = 0" : " AND game_result <> 0");
        }
        if (StringUtils.hasText(start)) { where.append(" AND event_time >= ?"); args.add(normalizeBoundary(start, false)); }
        if (StringUtils.hasText(end)) { where.append(" AND event_time <= ?"); args.add(normalizeBoundary(end, true)); }
    }

    private RowMapper<Map<String, Object>> accountMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getString("id")); row.put("gamePlayerId", rs.getString("game_player_id"));
            row.put("playerName", rs.getString("player_name")); row.put("playerIcon", rs.getString("player_icon"));
            row.put("level", rs.getInt("level_value")); row.put("area", rs.getInt("area_value"));
            row.put("active", rs.getBoolean("is_active")); row.put("updatedAt", rs.getString("updated_at"));
            return row;
        };
    }

    private RowMapper<Map<String, Object>> battleMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getString("id")); row.put("accountId", rs.getString("account_id")); row.put("roomId", rs.getString("room_id"));
            row.put("mapId", rs.getString("map_id")); row.put("mapName", rs.getString("map_name")); row.put("eventTime", rs.getString("event_time"));
            row.put("startTime", rs.getString("start_time")); row.put("durationSeconds", rs.getInt("duration_seconds"));
            row.put("gameResult", rs.getInt("game_result")); row.put("gameRule", rs.getInt("game_rule"));
            row.put("roleId", rs.getString("role_id")); row.put("roleName", rs.getString("role_name"));
            row.put("killCount", rs.getInt("kill_count")); row.put("playerKillCount", rs.getInt("player_kill_count"));
            row.put("assistCount", rs.getInt("assist_count")); row.put("rescueCount", rs.getInt("rescue_count"));
            row.put("collectionValue", rs.getLong("collection_value")); row.put("gainedValue", rs.getLong("gained_value"));
            row.put("profitLoss", rs.getLong("profit_loss")); row.put("equipmentValue", rs.getLong("equipment_value"));
            row.put("ranked", rs.getBoolean("ranked")); row.put("leftEarly", rs.getBoolean("left_early"));
            row.put("blueBox", rs.getBoolean("blue_box")); row.put("queue", rs.getString("queue_name"));
            return row;
        };
    }

    private RowMapper<Map<String, Object>> playerMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("gamePlayerId", rs.getString("game_player_id")); row.put("playerName", rs.getString("player_name"));
            row.put("playerIcon", rs.getString("player_icon")); row.put("teamId", rs.getInt("team_id"));
            row.put("roleId", rs.getString("role_id")); row.put("roleName", rs.getString("role_name"));
            row.put("killCount", rs.getInt("kill_count")); row.put("playerKillCount", rs.getInt("player_kill_count"));
            row.put("assistCount", rs.getInt("assist_count")); row.put("rescueCount", rs.getInt("rescue_count"));
            row.put("damage", rs.getLong("damage")); row.put("gainedValue", rs.getLong("gained_value"));
            row.put("profitLoss", rs.getLong("profit_loss")); row.put("self", rs.getBoolean("is_self"));
            return row;
        };
    }

    private RowMapper<Map<String, Object>> itemMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("gamePlayerId", rs.getString("game_player_id")); row.put("itemId", rs.getString("item_id"));
            row.put("itemName", rs.getString("item_name")); row.put("pictureUrl", rs.getString("picture_url"));
            row.put("grade", rs.getInt("grade")); row.put("quantity", rs.getInt("quantity"));
            row.put("unitPrice", rs.getLong("unit_price")); row.put("totalValue", rs.getLong("unit_price") * rs.getInt("quantity"));
            return row;
        };
    }

    private Map<String, Object> emptySummary() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("totalBattles", 0); out.put("escapedBattles", 0); out.put("escapeRate", 0);
        out.put("playerKills", 0); out.put("totalProfit", 0); out.put("averageProfit", 0);
        out.put("averageDurationSeconds", 0); out.put("itemCount", 0); out.put("activeAccount", null);
        return out;
    }

    private static class Aggregate {
        long count; long escaped; long kills; long profit; long duration;
    }

    private void addAggregate(Map<String, Aggregate> grouped, String key, Map<String, Object> battle) {
        Aggregate a = grouped.computeIfAbsent(key, ignored -> new Aggregate());
        a.count++; if (number(battle.get("gameResult")).intValue() == 0) a.escaped++;
        a.kills += number(battle.get("playerKillCount")).longValue();
        a.profit += number(battle.get("profitLoss")).longValue();
        a.duration += number(battle.get("durationSeconds")).longValue();
    }

    private List<Map<String, Object>> aggregateRows(Map<String, Aggregate> grouped) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Aggregate> entry : grouped.entrySet()) {
            Aggregate a = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("label", entry.getKey()); row.put("battles", a.count); row.put("escaped", a.escaped);
            row.put("escapeRate", a.count == 0 ? 0 : round(a.escaped * 100d / a.count));
            row.put("playerKills", a.kills); row.put("profit", a.profit);
            row.put("averageProfit", a.count == 0 ? 0 : Math.round(a.profit / (double) a.count));
            row.put("averageDurationSeconds", a.count == 0 ? 0 : Math.round(a.duration / (double) a.count));
            rows.add(row);
        }
        Collections.sort(rows, (a, b) -> Long.compare(number(b.get("battles")).longValue(), number(a.get("battles")).longValue()));
        return rows;
    }

    private List<Map<String, Object>> highlightRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>(rows);
        Collections.sort(copy, (a, b) -> Long.compare(number(b.get("profitLoss")).longValue(), number(a.get("profitLoss")).longValue()));
        return copy.subList(0, Math.min(5, copy.size()));
    }

    private String bucketKey(String value, String bucket) {
        LocalDate date = parseDate(value);
        if ("month".equals(bucket)) return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        if ("week".equals(bucket)) {
            WeekFields fields = WeekFields.ISO;
            return date.get(fields.weekBasedYear()) + "-W" + String.format("%02d", date.get(fields.weekOfWeekBasedYear()));
        }
        return date.toString();
    }

    private LocalDate parseDate(String value) {
        if (value != null && value.length() >= 10) {
            try { return LocalDate.parse(value.substring(0, 10)); } catch (DateTimeParseException ignored) { }
        }
        return LocalDate.ofEpochDay(0);
    }

    private String normalizeBoundary(String value, boolean end) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() == 10) return clean + (end ? "T23:59:59.999Z" : "T00:00:00Z");
        return normalizeTime(clean);
    }

    private String normalizeTime(Object raw) {
        if (raw == null) return "";
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            if (Math.abs(value) < 100000000000L) value *= 1000;
            return Instant.ofEpochMilli(value).toString();
        }
        String text = String.valueOf(raw).trim();
        if (!StringUtils.hasText(text)) return "";
        if (text.matches("^-?\\d{10,13}$")) {
            try {
                long value = Long.parseLong(text);
                if (text.length() <= 10) value *= 1000;
                return Instant.ofEpochMilli(value).toString();
            } catch (NumberFormatException ignored) { }
        }
        if (text.length() >= 19 && text.charAt(10) == ' ') text = text.substring(0, 10) + "T" + text.substring(11);
        if (text.length() == 19 && text.charAt(10) == 'T') text += "Z";
        return text;
    }

    private String now() { return Instant.now().toString(); }
    private double round(double value) { return Math.round(value * 10d) / 10d; }
    private String label(Object value, String fallback) { return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value); }
    private Number number(Object value) { if (value instanceof Number) return (Number) value; try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { return 0; } }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        return null;
    }

    private String text(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Map<String, Object> map, int fallback, String... keys) {
        Object value = first(map, keys);
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; }
    }

    private long longValue(Map<String, Object> map, long fallback, String... keys) {
        Object value = first(map, keys);
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return fallback; }
    }

    private boolean boolValue(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value != null && Arrays.asList("true", "1", "yes", "on").contains(String.valueOf(value).toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }
}
