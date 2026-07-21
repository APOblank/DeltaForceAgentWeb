package com.deltaforce.agentweb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataCenterServiceTest {
    private DataCenterService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:stats-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        service = new DataCenterService(new JdbcTemplate(dataSource));
    }

    @Test
    void dataIsAlwaysScopedToAuthenticatedOwnerEvenWhenRoomIdsMatch() {
        service.importData("user-a", payload("player-a", "阿尔法", "same-room", 1200000, 3));
        service.importData("user-b", payload("player-b", "Bravo", "same-room", -300000, 1));

        Map<String, Object> summaryA = service.summary("user-a", null);
        Map<String, Object> summaryB = service.summary("user-b", null);
        assertEquals(1200000L, summaryA.get("totalProfit"));
        assertEquals(-300000L, summaryB.get("totalProfit"));

        Map<String, Object> recordsA = service.records("user-a", null, 1, 20, null, null, null, null);
        Map<String, Object> recordsB = service.records("user-b", null, 1, 20, null, null, null, null);
        assertEquals(1L, recordsA.get("total"));
        assertEquals(1L, recordsB.get("total"));
        String battleB = String.valueOf(firstRow(recordsB).get("id"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.battleDetail("user-a", battleB));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void repeatedImportUpdatesBattleAndReplacesChildrenWithoutDuplicating() {
        Map<String, Object> initial = payload("player-a", "阿尔法", "room-1", 100, 2);
        service.importData("user-a", initial);
        Map<String, Object> updated = payload("player-a", "阿尔法", "room-1", 999, 5);
        Map<String, Object> result = service.importData("user-a", updated);

        assertEquals(0, result.get("insertedBattles"));
        assertEquals(1, result.get("updatedBattles"));
        Map<String, Object> records = service.records("user-a", null, 1, 20, null, null, null, null);
        assertEquals(1L, records.get("total"));
        Map<String, Object> row = firstRow(records);
        assertEquals(999L, row.get("profitLoss"));
        assertEquals(5, row.get("playerKillCount"));
        Map<String, Object> detail = service.battleDetail("user-a", String.valueOf(row.get("id")));
        assertEquals(1, ((List<?>) detail.get("players")).size());
        assertEquals(1, ((List<?>) detail.get("items")).size());
    }

    @Test
    void importedOwnershipFieldsAreIgnoredAndAccountDeletionIsOwnerScoped() {
        Map<String, Object> payload = payload("player-a", "阿尔法", "room-1", 100, 2);
        payload.put("ownerUserId", "attacker-controlled-owner");
        service.importData("real-user", payload);

        assertFalse(service.accounts("real-user").isEmpty());
        assertEquals(0, service.accounts("attacker-controlled-owner").size());
        String accountId = String.valueOf(service.accounts("real-user").get(0).get("id"));
        assertThrows(ResponseStatusException.class, () -> service.deleteAccount("another-user", accountId));
        assertEquals(1, service.accounts("real-user").size());
    }

    @Test
    void importsLegacyDataCenterZipBackup() throws Exception {
        byte[] backup = legacyBackup();
        Map<String, Object> result = service.importLegacyBackup("legacy-owner", backup);

        assertEquals(1, result.get("accountsImported"));
        assertEquals(1, result.get("insertedBattles"));
        Map<String, Object> summary = service.summary("legacy-owner", null);
        assertEquals(1L, summary.get("totalBattles"));
        assertEquals(880000L, summary.get("totalProfit"));
        Map<String, Object> records = service.records("legacy-owner", null, 1, 20, null, null, null, null);
        Map<String, Object> detail = service.battleDetail("legacy-owner", String.valueOf(firstRow(records).get("id")));
        assertEquals(1, ((List<?>) detail.get("players")).size());
        assertEquals(1, ((List<?>) detail.get("items")).size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRow(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("data")).get(0);
    }

    private Map<String, Object> payload(String playerId, String playerName, String roomId, long profit, int kills) {
        Map<String, Object> account = new LinkedHashMap<String, Object>();
        account.put("playerId", playerId);
        account.put("playerName", playerName);
        account.put("level", 42);

        Map<String, Object> player = new LinkedHashMap<String, Object>();
        player.put("playerId", playerId);
        player.put("playerName", playerName);
        player.put("isSelf", true);
        player.put("playerKillCount", kills);

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("itemId", "item-1");
        item.put("itemName", "测试物资");
        item.put("quantity", 2);
        item.put("unitPrice", 50000);

        Map<String, Object> battle = new LinkedHashMap<String, Object>();
        battle.put("roomId", roomId);
        battle.put("eventTime", "2026-07-21T12:00:00Z");
        battle.put("mapName", "零号大坝");
        battle.put("roleName", "蜂医");
        battle.put("gameResult", 0);
        battle.put("playerKillCount", kills);
        battle.put("profitLoss", profit);
        battle.put("durationSeconds", 900);
        battle.put("players", Arrays.asList(player));
        battle.put("items", Arrays.asList(item));

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("account", account);
        payload.put("battles", Arrays.asList(battle));
        return payload;
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
                zip.write("{\"backup_version\":1}".getBytes("UTF-8"));
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
