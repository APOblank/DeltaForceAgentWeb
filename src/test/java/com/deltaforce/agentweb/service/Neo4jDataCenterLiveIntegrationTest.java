package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.DeltaForceAgentWebApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "liveNeo4j", matches = "true")
@SpringBootTest(
        classes = DeltaForceAgentWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:neo4j-live-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "delta-force.neo4j.stats-migration-enabled=false"
        })
class Neo4jDataCenterLiveIntegrationTest {
    @Autowired
    private Neo4jDataCenterService service;

    @Test
    void graphQueriesEnforceTenantOwnershipAndRemainIdempotent() {
        String suffix = UUID.randomUUID().toString();
        String userA = "live-test-a-" + suffix;
        String userB = "live-test-b-" + suffix;
        try {
            service.importData(userA, payload("player-a", "Alpha", 1200, 3));
            service.importData(userB, payload("player-b", "Bravo", -300, 1));
            assertEquals(1200L, service.summary(userA, null).get("totalProfit"));
            assertEquals(-300L, service.summary(userB, null).get("totalProfit"));

            Map<String, Object> rowsA = service.records(userA, null, 1, 20, null, null, null, null);
            String battleA = String.valueOf(firstRow(rowsA).get("id"));
            Map<String, Object> detailA = service.battleDetail(userA, battleA);
            assertEquals(1, ((List<?>) detailA.get("players")).size());
            assertEquals(1, ((List<?>) detailA.get("items")).size());
            assertEquals(1, ((List<?>) service.items(userA, null, null, null).get("data")).size());
            assertEquals(1, service.analysis(userA, null, null, null).get("totalBattles"));
            assertEquals(1, ((List<?>) service.trends(userA, null, "day", null, null).get("data")).size());
            assertEquals(1, ((List<?>) service.exportData(userA, null).get("battles")).size());

            Map<String, Object> rowsB = service.records(userB, null, 1, 20, null, null, null, null);
            String battleB = String.valueOf(firstRow(rowsB).get("id"));
            ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                    () -> service.battleDetail(userA, battleB));
            assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatus());

            Map<String, Object> repeated = service.importData(userA, payload("player-a", "Alpha", 9999, 7));
            assertEquals(0, repeated.get("insertedBattles"));
            assertEquals(1, repeated.get("updatedBattles"));
            assertEquals(1L, service.records(userA, null, 1, 20, null, null, null, null).get("total"));
            assertEquals(9999L, service.summary(userA, null).get("totalProfit"));
        } finally {
            service.clearData(userA);
            service.clearData(userB);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRow(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("data")).get(0);
    }

    private Map<String, Object> payload(String playerId, String name, long profit, int kills) {
        Map<String, Object> account = new LinkedHashMap<String, Object>();
        account.put("playerId", playerId);
        account.put("playerName", name);
        Map<String, Object> player = new LinkedHashMap<String, Object>();
        player.put("playerId", playerId);
        player.put("playerName", name);
        player.put("isSelf", true);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("itemId", "test-item");
        item.put("itemName", "测试物资");
        item.put("quantity", 2);
        item.put("unitPrice", 50L);
        Map<String, Object> battle = new LinkedHashMap<String, Object>();
        battle.put("roomId", "same-room");
        battle.put("eventTime", "2026-07-21T12:00:00Z");
        battle.put("gameResult", 0);
        battle.put("profitLoss", profit);
        battle.put("playerKillCount", kills);
        battle.put("players", Arrays.asList(player));
        battle.put("items", Arrays.asList(item));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("account", account);
        payload.put("battles", Arrays.asList(battle));
        payload.put("ownerUserId", "client-controlled-value-must-be-ignored");
        return payload;
    }
}
