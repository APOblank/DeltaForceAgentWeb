package com.deltaforce.agentweb.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyBackupParserTest {
    private final LegacyBackupParser parser = new LegacyBackupParser();

    @Test
    void parsesLegacySqliteBackupIntoCanonicalPayloads() throws Exception {
        List<Map<String, Object>> payloads = parser.parse(legacyBackup());

        assertEquals(1, payloads.size());
        Map<String, Object> account = map(payloads.get(0).get("account"));
        assertEquals("legacy-player", account.get("playerId"));
        assertEquals("旧档案玩家", account.get("playerName"));

        List<Map<String, Object>> battles = rows(payloads.get(0).get("battles"));
        assertEquals(1, battles.size());
        assertEquals("legacy-room", battles.get(0).get("roomId"));
        assertEquals(880000L, battles.get(0).get("profitLoss"));
        assertEquals(1, rows(battles.get(0).get("players")).size());
        assertEquals(1, rows(battles.get(0).get("items")).size());
    }

    @Test
    void rejectsBackupWithoutSqliteDatabase() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> parser.parse(bytes.toByteArray()));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private byte[] legacyBackup() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path db = Files.createTempFile("legacy-data-center-", ".sqlite3");
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE Account(player_id TEXT, player_name TEXT, player_icon TEXT, level INTEGER, area INTEGER, is_active INTEGER, last_login TEXT)");
                statement.execute("CREATE TABLE MapID(map_id INTEGER, map_name TEXT)");
                statement.execute("CREATE TABLE Role(role_id INTEGER, role_name TEXT)");
                statement.execute("CREATE TABLE Record(room_id TEXT, map_id INTEGER, event_time TEXT, start_time TEXT, duration_s INTEGER, game_result INTEGER, game_rule INTEGER, role_id INTEGER, kill_cnt INTEGER, kill_player INTEGER, assist_cnt INTEGER, rescue INTEGER, collection_price INTEGER, gained_price INTEGER, profit_loss INTEGER, original_equipment_price INTEGER, is_rank_match INTEGER, is_leave INTEGER, has_blue_box INTEGER, queue TEXT, player_id TEXT)");
                statement.execute("CREATE TABLE BattleDetail(room_id TEXT, player_id TEXT, player_name TEXT, player_icon TEXT, team_id INTEGER, armed_force_id INTEGER, kill_cnt INTEGER, kill_player INTEGER, assist_cnt INTEGER, rescue INTEGER, gained_price INTEGER, profit_loss INTEGER, is_self INTEGER)");
                statement.execute("CREATE TABLE battles_items(room_id TEXT, player_id TEXT, item_id TEXT, item_name TEXT, pic TEXT, grade INTEGER, num INTEGER, price INTEGER)");
                statement.execute("INSERT INTO Account VALUES('legacy-player','旧档案玩家','',55,36,1,'2026-07-21')");
                statement.execute("INSERT INTO MapID VALUES(1,'航天基地')");
                statement.execute("INSERT INTO Role VALUES(2,'红狼')");
                statement.execute("INSERT INTO Record VALUES('legacy-room',1,'2026-07-20 18:00:00','2026-07-20 17:45:00',900,0,1,2,4,3,2,1,1000000,1400000,880000,520000,1,0,0,'sol','legacy-player')");
                statement.execute("INSERT INTO BattleDetail VALUES('legacy-room','legacy-player','旧档案玩家','',1,2,4,3,2,1,1400000,880000,1)");
                statement.execute("INSERT INTO battles_items VALUES('legacy-room','legacy-player','red-1','曼德尔砖','',6,1,1200000)");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write("{\"backup_version\":1}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("database.sqlite3"));
                zip.write(Files.readAllBytes(db));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } finally {
            Files.deleteIfExists(db);
        }
    }
}
