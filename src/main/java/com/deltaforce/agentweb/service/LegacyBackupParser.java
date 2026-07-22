package com.deltaforce.agentweb.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Parses the original Data Center SQLite backup without using an application relational database. */
@Service
public class LegacyBackupParser {
    private static final int MAX_ZIP_BYTES = 100 * 1024 * 1024;
    private static final int MAX_SQLITE_BYTES = 250 * 1024 * 1024;

    public List<Map<String, Object>> parse(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) throw badRequest("备份文件内容为空");
        if (zipBytes.length > MAX_ZIP_BYTES) throw badRequest("备份文件不能超过 100MB");
        return parse(new ByteArrayInputStream(zipBytes));
    }

    public List<Map<String, Object>> parse(InputStream zipStream) {
        if (zipStream == null) throw badRequest("备份文件内容为空");
        Path sqliteFile = null;
        try {
            sqliteFile = Files.createTempFile("delta-force-import-", ".sqlite3");
            extractSqlite(zipStream, sqliteFile);
            Class.forName("org.sqlite.JDBC");
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
                List<Map<String, Object>> payloads = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> account : readAccounts(source)) {
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("account", account);
                    payload.put("battles", readBattles(source, String.valueOf(account.get("playerId"))));
                    payloads.add(payload);
                }
                if (payloads.isEmpty()) throw badRequest("备份中没有游戏账号");
                return payloads;
            }
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

    private void extractSqlite(InputStream zipStream, Path sqliteFile) throws IOException {
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && ("database.sqlite3".equals(name) || name.endsWith("/database.sqlite3"))) {
                    byte[] chunk = new byte[8192];
                    int read;
                    int total = 0;
                    try (OutputStream output = Files.newOutputStream(sqliteFile)) {
                        while ((read = zip.read(chunk)) >= 0) {
                            total += read;
                            if (total > MAX_SQLITE_BYTES) throw badRequest("备份数据库解压后不能超过 250MB");
                            output.write(chunk, 0, read);
                        }
                    }
                    found = true;
                    break;
                }
            }
        }
        if (!found) throw badRequest("不是有效的数据中心备份：缺少 database.sqlite3");
    }

    private List<Map<String, Object>> readAccounts(Connection source) throws SQLException {
        List<Map<String, Object>> accounts = new ArrayList<Map<String, Object>>();
        try (PreparedStatement statement = source.prepareStatement(
                "SELECT player_id, player_name, player_icon, level, area FROM Account ORDER BY is_active DESC, last_login DESC");
             ResultSet rs = statement.executeQuery()) {
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
        return accounts;
    }

    private List<Map<String, Object>> readBattles(Connection source, String playerId) throws SQLException {
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
                    battle.put("players", readPlayers(source, roomId)); battle.put("items", readItems(source, roomId));
                    battles.add(battle);
                }
            }
        }
        return battles;
    }

    private List<Map<String, Object>> readPlayers(Connection source, String roomId) throws SQLException {
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

    private List<Map<String, Object>> readItems(Connection source, String roomId) throws SQLException {
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

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
