package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.StatsDatabaseInitializer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class Neo4jDataCenterService {
    private final Driver driver;
    private final StatsDatabaseInitializer initializer;
    private final DataCenterService legacyStore;

    public Neo4jDataCenterService(Driver driver, StatsDatabaseInitializer initializer, DataCenterService legacyStore) {
        this.driver = driver;
        this.initializer = initializer;
        this.legacyStore = legacyStore;
    }

    public List<Map<String, Object>> accounts(String userId) {
        requireUserId(userId);
        try (Session session = session()) {
            return session.readTransaction(tx -> queryMaps(tx,
                    "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {ownerUserId:$userId}) " +
                            "RETURN a{.*} AS value ORDER BY a.active DESC, a.updatedAt DESC",
                    params("userId", userId), "value", "ownerUserId"));
        }
    }

    public Map<String, Object> selectAccount(String userId, String accountId) {
        requireAccount(userId, accountId);
        try (Session session = session()) {
            session.writeTransaction(tx -> {
                tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {ownerUserId:$userId}) SET a.active=false",
                        params("userId", userId)).consume();
                tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId}) " +
                                "SET a.active=true,a.updatedAt=$now",
                        params("userId", userId, "accountId", accountId, "now", now())).consume();
                return null;
            });
        }
        return requireAccount(userId, accountId);
    }

    public Map<String, Object> deleteAccount(String userId, String accountId) {
        requireAccount(userId, accountId);
        try (Session session = session()) {
            session.writeTransaction(tx -> {
                Map<String, Object> p = params("userId", userId, "accountId", accountId);
                tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId})" +
                        "-[:HAS_BATTLE]->(:Battle)-[:HAS_PLAYER]->(x:BattlePlayer {ownerUserId:$userId}) DETACH DELETE x", p).consume();
                tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId})" +
                        "-[:HAS_BATTLE]->(:Battle)-[:HAS_ITEM]->(x:BattleItem {ownerUserId:$userId}) DETACH DELETE x", p).consume();
                tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId})" +
                        "-[:HAS_BATTLE]->(b:Battle {ownerUserId:$userId}) DETACH DELETE b", p).consume();
                tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId}) DETACH DELETE a", p).consume();
                return null;
            });
        }
        List<Map<String, Object>> remaining = accounts(userId);
        if (!remaining.isEmpty() && !bool(remaining.get(0).get("active"))) {
            selectAccount(userId, String.valueOf(remaining.get(0).get("id")));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", true);
        out.put("accounts", accounts(userId));
        return out;
    }

    public Map<String, Object> importData(String userId, Map<String, Object> payload) {
        requireUserId(userId);
        if (payload == null) throw badRequest("导入内容不能为空");
        Map<String, Object> accountInput = mapValue(first(payload, "account", "gameAccount"));
        if (accountInput.isEmpty()) accountInput = payload;
        final String gamePlayerId = text(accountInput, "playerId", "player_id", "gamePlayerId", "game_player_id");
        String playerName = text(accountInput, "playerName", "player_name", "name");
        if (!StringUtils.hasText(gamePlayerId)) throw badRequest("account.playerId 不能为空");
        if (!StringUtils.hasText(playerName)) playerName = gamePlayerId;
        final String accountId = stableId("account", userId, gamePlayerId);
        final Map<String, Object> accountProps = new LinkedHashMap<String, Object>();
        accountProps.put("id", accountId);
        accountProps.put("ownerUserId", userId);
        accountProps.put("gamePlayerId", gamePlayerId);
        accountProps.put("playerName", playerName);
        accountProps.put("playerIcon", text(accountInput, "playerIcon", "player_icon", "icon"));
        accountProps.put("level", intValue(accountInput, 0, "level", "levelValue"));
        accountProps.put("area", intValue(accountInput, 36, "area", "areaValue"));
        accountProps.put("updatedAt", now());

        final List<Object> battles = listValue(first(payload, "battles", "records", "data"));
        final int[] counts = new int[4];
        try (Session session = session()) {
            session.writeTransaction(tx -> {
                tx.run("MERGE (u:StatsUser {userId:$userId}) ON CREATE SET u.createdAt=$now SET u.updatedAt=$now",
                        params("userId", userId, "now", now())).consume();
                tx.run("MATCH (u:StatsUser {userId:$userId}) OPTIONAL MATCH (u)-[:OWNS_ACCOUNT]->(old:GameAccount {ownerUserId:$userId}) " +
                                "WITH u,old SET old.active=false WITH DISTINCT u " +
                                "MERGE (a:GameAccount {id:$accountId}) SET a += $props,a.active=true " +
                                "MERGE (u)-[:OWNS_ACCOUNT]->(a)",
                        params("userId", userId, "accountId", accountId, "props", accountProps)).consume();

                int battleIndex = 0;
                for (Object raw : battles) {
                    Map<String, Object> battle = mapValue(raw);
                    String roomId = text(battle, "roomId", "room_id");
                    if (!StringUtils.hasText(roomId)) continue;
                    String battleId = stableId("battle", userId, gamePlayerId, roomId);
                    boolean exists = tx.run(
                            "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount {id:$accountId,ownerUserId:$userId})" +
                                    "-[:HAS_BATTLE]->(b:Battle {id:$battleId,ownerUserId:$userId}) RETURN count(b)>0 AS found",
                            params("userId", userId, "accountId", accountId, "battleId", battleId)).single().get("found").asBoolean();
                    Map<String, Object> battleProps = battleProperties(userId, accountId, battleId, roomId, battle);
                    tx.run("MATCH (u:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId}) " +
                                    "MERGE (b:Battle {id:$battleId}) SET b += $props MERGE (a)-[:HAS_BATTLE]->(b)",
                            params("userId", userId, "accountId", accountId, "battleId", battleId, "props", battleProps)).consume();
                    replaceChildren(tx, userId, battleId, gamePlayerId, battle, battleIndex++);
                    if (exists) counts[1]++; else counts[0]++;
                    counts[2] += playerRows(battle).size();
                    counts[3] += listValue(first(battle, "items", "battleItems", "battle_items")).size();
                }
                return null;
            });
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accountId", accountId);
        result.put("gamePlayerId", gamePlayerId);
        result.put("playerName", playerName);
        result.put("insertedBattles", counts[0]);
        result.put("updatedBattles", counts[1]);
        result.put("players", counts[2]);
        result.put("items", counts[3]);
        return result;
    }

    // Compatibility bridge: parse the original encrypted/SQLite backup with the
    // already tested legacy importer, then immediately move its canonical export
    // into the tenant-scoped graph database.
    public Map<String, Object> importLegacyBackup(String userId, byte[] zipBytes) {
        Map<String, Object> result = legacyStore.importLegacyBackup(userId, zipBytes);
        migrateUserFromLegacyStore(userId);
        return result;
    }

    public Map<String, Object> summary(String userId, String requestedAccountId) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        Map<String, Object> result = emptySummary();
        result.put("accounts", accounts(userId));
        if (account == null) return result;
        List<Map<String, Object>> battles = filteredBattles(userId, String.valueOf(account.get("id")), null, null);
        long escaped = 0, kills = 0, profit = 0, duration = 0;
        for (Map<String, Object> battle : battles) {
            if (number(battle.get("gameResult")).intValue() == 0) escaped++;
            kills += number(battle.get("playerKillCount")).longValue();
            profit += number(battle.get("profitLoss")).longValue();
            duration += number(battle.get("durationSeconds")).longValue();
        }
        long total = battles.size();
        result.put("totalBattles", total);
        result.put("escapedBattles", escaped);
        result.put("escapeRate", total == 0 ? 0 : round(escaped * 100d / total));
        result.put("playerKills", kills);
        result.put("totalProfit", profit);
        result.put("averageDurationSeconds", total == 0 ? 0 : Math.round(duration / (double) total));
        result.put("averageProfit", total == 0 ? 0 : Math.round(profit / (double) total));
        result.put("activeAccount", account);
        try (Session session = session()) {
            long itemCount = session.readTransaction(tx -> tx.run(
                    "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount {id:$accountId,ownerUserId:$userId})" +
                            "-[:HAS_BATTLE]->(:Battle {ownerUserId:$userId})-[:HAS_ITEM]->(i:BattleItem {ownerUserId:$userId}) RETURN count(i) AS total",
                    params("userId", userId, "accountId", account.get("id"))).single().get("total").asLong());
            result.put("itemCount", itemCount);
        }
        return result;
    }

    public Map<String, Object> records(String userId, String requestedAccountId, int page, int size,
                                       String mapName, String resultValue, String start, String end) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("page", safePage);
        out.put("size", safeSize);
        if (account == null) {
            out.put("total", 0);
            out.put("data", Collections.emptyList());
            out.put("maps", Collections.emptyList());
            return out;
        }
        String accountId = String.valueOf(account.get("id"));
        StringBuilder where = new StringBuilder(" WHERE b.ownerUserId=$userId");
        Map<String, Object> p = params("userId", userId, "accountId", accountId,
                "skip", (safePage - 1) * safeSize, "limit", safeSize);
        addFilters(where, p, mapName, resultValue, start, end);
        String match = "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount {id:$accountId,ownerUserId:$userId})" +
                "-[:HAS_BATTLE]->(b:Battle)" + where;
        try (Session session = session()) {
            session.readTransaction(tx -> {
                out.put("total", tx.run(match + " RETURN count(b) AS total", p).single().get("total").asLong());
                List<Map<String, Object>> rows = queryMaps(tx,
                        match + " OPTIONAL MATCH (b)-[:HAS_ITEM]->(i:BattleItem {ownerUserId:$userId}) " +
                                "WITH b,count(i) AS itemCount ORDER BY b.eventTime DESC SKIP $skip LIMIT $limit " +
                                "RETURN b{.*,itemCount:itemCount} AS value",
                        p, "value", "ownerUserId");
                out.put("data", rows);
                Result maps = tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount {id:$accountId,ownerUserId:$userId})" +
                        "-[:HAS_BATTLE]->(b:Battle {ownerUserId:$userId}) WHERE coalesce(b.mapName,'')<>'' RETURN DISTINCT b.mapName AS name ORDER BY name",
                        params("userId", userId, "accountId", accountId));
                List<String> names = new ArrayList<String>();
                while (maps.hasNext()) names.add(maps.next().get("name").asString());
                out.put("maps", names);
                return null;
            });
        }
        return out;
    }

    public Map<String, Object> battleDetail(String userId, String battleId) {
        Map<String, Object> battle = requireBattle(userId, battleId);
        Map<String, Object> p = params("userId", userId, "battleId", battleId);
        try (Session session = session()) {
            return session.readTransaction(tx -> {
                List<Map<String, Object>> players = queryMaps(tx,
                        "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount)-[:HAS_BATTLE]->" +
                                "(:Battle {id:$battleId,ownerUserId:$userId})-[:HAS_PLAYER]->(x:BattlePlayer {ownerUserId:$userId}) " +
                                "RETURN x{.*} AS value ORDER BY x.self DESC,x.teamId,x.playerName",
                        p, "value", "id", "ownerUserId");
                List<Map<String, Object>> items = queryMaps(tx,
                        "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount)-[:HAS_BATTLE]->" +
                                "(:Battle {id:$battleId,ownerUserId:$userId})-[:HAS_ITEM]->(x:BattleItem {ownerUserId:$userId}) " +
                                "RETURN x{.*,totalValue:x.unitPrice*x.quantity} AS value ORDER BY value.totalValue DESC,x.itemName",
                        p, "value", "id", "ownerUserId");
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("battle", battle);
                out.put("players", players);
                out.put("items", items);
                return out;
            });
        }
    }

    public Map<String, Object> analysis(String userId, String accountId, String start, String end) {
        List<Map<String, Object>> rows = filteredBattles(userId, accountId, start, end);
        Map<String, Aggregate> maps = new LinkedHashMap<String, Aggregate>();
        Map<String, Aggregate> roles = new LinkedHashMap<String, Aggregate>();
        Map<String, Aggregate> results = new LinkedHashMap<String, Aggregate>();
        for (Map<String, Object> row : rows) {
            addAggregate(maps, label(row.get("mapName"), "未知地图"), row);
            addAggregate(roles, label(row.get("roleName"), "未知干员"), row);
            addAggregate(results, number(row.get("gameResult")).intValue() == 0 ? "成功撤离" : "未撤离", row);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("totalBattles", rows.size());
        out.put("maps", aggregateRows(maps));
        out.put("roles", aggregateRows(roles));
        out.put("results", aggregateRows(results));
        out.put("highlights", highlightRows(rows));
        return out;
    }

    public Map<String, Object> trends(String userId, String accountId, String bucket, String start, String end) {
        String safeBucket = Arrays.asList("day", "week", "month").contains(bucket) ? bucket : "day";
        Map<String, Aggregate> grouped = new LinkedHashMap<String, Aggregate>();
        for (Map<String, Object> row : filteredBattles(userId, accountId, start, end)) {
            addAggregate(grouped, bucketKey(String.valueOf(row.get("eventTime")), safeBucket), row);
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
        StringBuilder where = new StringBuilder(" WHERE b.ownerUserId=$userId AND i.ownerUserId=$userId");
        Map<String, Object> p = params("userId", userId, "accountId", account.get("id"));
        if (StringUtils.hasText(start)) { where.append(" AND b.eventTime >= $start"); p.put("start", normalizeBoundary(start, false)); }
        if (StringUtils.hasText(end)) { where.append(" AND b.eventTime <= $end"); p.put("end", normalizeBoundary(end, true)); }
        try (Session session = session()) {
            List<Map<String, Object>> data = session.readTransaction(tx -> queryMaps(tx,
                    "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount {id:$accountId,ownerUserId:$userId})" +
                            "-[:HAS_BATTLE]->(b:Battle)-[:HAS_ITEM]->(i:BattleItem)" + where +
                            " WITH i.itemId AS itemId,i.itemName AS itemName,i.grade AS grade,sum(i.quantity) AS quantity," +
                            "sum(i.quantity*i.unitPrice) AS totalValue,max(i.unitPrice) AS maxUnitPrice " +
                            "RETURN {itemId:itemId,itemName:itemName,grade:grade,quantity:quantity," +
                            "totalValue:totalValue,maxUnitPrice:maxUnitPrice} AS value " +
                            "ORDER BY value.totalValue DESC LIMIT 500", p, "value"));
            long totalValue = 0;
            for (Map<String, Object> item : data) totalValue += number(item.get("totalValue")).longValue();
            out.put("data", data);
            out.put("totalValue", totalValue);
            return out;
        }
    }

    public Map<String, Object> exportData(String userId, String requestedAccountId) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        if (account == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未绑定游戏账号");
        List<Map<String, Object>> battles = filteredBattles(userId, String.valueOf(account.get("id")), null, null);
        for (Map<String, Object> battle : battles) {
            Map<String, Object> detail = battleDetail(userId, String.valueOf(battle.get("id")));
            battle.put("players", detail.get("players"));
            battle.put("items", detail.get("items"));
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

    public Map<String, Object> clearData(String userId) {
        int count = accounts(userId).size();
        for (Map<String, Object> account : new ArrayList<Map<String, Object>>(accounts(userId))) {
            deleteAccount(userId, String.valueOf(account.get("id")));
        }
        try (Session session = session()) {
            session.writeTransaction(tx -> {
                tx.run("MATCH (u:StatsUser {userId:$userId}) WHERE NOT (u)-[:OWNS_ACCOUNT]->() DETACH DELETE u",
                        params("userId", userId)).consume();
                return null;
            });
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", true);
        out.put("accounts", count);
        return out;
    }

    public Map<String, Object> buildAiContext(String userId, String question) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("source", initializer.database());
        Map<String, Object> summary = summary(userId, null);
        summary.remove("accounts");
        context.put("summary", summary);
        if (number(summary.get("totalBattles")).longValue() > 0) {
            context.put("recentBattles", records(userId, null, 1, 20, null, null, null, null).get("data"));
            String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
            if (q.contains("分析") || q.contains("地图") || q.contains("干员") || q.contains("表现")) {
                context.put("analysis", analysis(userId, null, null, null));
            }
        }
        return context;
    }

    public void migrateUserFromLegacyStore(String userId) {
        for (Map<String, Object> account : legacyStore.accounts(userId)) {
            importData(userId, legacyStore.exportData(userId, String.valueOf(account.get("id"))));
        }
    }

    public boolean migrationCompleted(String marker) {
        try (Session session = session()) {
            return session.readTransaction(tx -> tx.run(
                    "MATCH (m:StatsMigration {id:$id}) RETURN count(m)>0 AS done", params("id", marker))
                    .single().get("done").asBoolean());
        }
    }

    public void markMigrationCompleted(String marker) {
        try (Session session = session()) {
            session.writeTransaction(tx -> {
                tx.run("MERGE (m:StatsMigration {id:$id}) SET m.completedAt=$now",
                        params("id", marker, "now", now())).consume();
                return null;
            });
        }
    }

    private void replaceChildren(Transaction tx, String userId, String battleId, String selfPlayerId,
                                 Map<String, Object> battle, int battleIndex) {
        Map<String, Object> p = params("userId", userId, "battleId", battleId);
        tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount)-[:HAS_BATTLE]->" +
                "(:Battle {id:$battleId,ownerUserId:$userId})-[:HAS_PLAYER]->(x:BattlePlayer {ownerUserId:$userId}) DETACH DELETE x", p).consume();
        tx.run("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount)-[:HAS_BATTLE]->" +
                "(:Battle {id:$battleId,ownerUserId:$userId})-[:HAS_ITEM]->(x:BattleItem {ownerUserId:$userId}) DETACH DELETE x", p).consume();

        List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (Object raw : playerRows(battle)) {
            Map<String, Object> source = mapValue(raw);
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("id", stableId("player", userId, battleId, String.valueOf(index++)));
            x.put("ownerUserId", userId);
            String pid = text(source, "playerId", "player_id", "gamePlayerId");
            x.put("gamePlayerId", pid);
            x.put("playerName", text(source, "playerName", "player_name", "name"));
            x.put("playerIcon", text(source, "playerIcon", "player_icon", "icon"));
            x.put("teamId", intValue(source, 0, "teamId", "team_id"));
            x.put("roleId", text(source, "roleId", "role_id", "armedForceId"));
            x.put("roleName", text(source, "roleName", "role_name"));
            x.put("killCount", intValue(source, 0, "killCount", "kill_cnt", "kills"));
            x.put("playerKillCount", intValue(source, 0, "playerKillCount", "kill_player", "playerKills"));
            x.put("assistCount", intValue(source, 0, "assistCount", "assist_cnt", "assists"));
            x.put("rescueCount", intValue(source, 0, "rescueCount", "rescue", "rescues"));
            x.put("damage", longValue(source, 0, "damage"));
            x.put("gainedValue", longValue(source, 0, "gainedValue", "gained_price"));
            x.put("profitLoss", longValue(source, 0, "profitLoss", "profit_loss", "profit"));
            x.put("self", boolValue(source, "self", "isSelf", "is_self") || selfPlayerId.equals(pid));
            players.add(x);
        }
        if (!players.isEmpty()) {
            tx.run("MATCH (b:Battle {id:$battleId,ownerUserId:$userId}) UNWIND $rows AS row " +
                            "CREATE (x:BattlePlayer {id:row.id}) SET x += row CREATE (b)-[:HAS_PLAYER]->(x)",
                    params("battleId", battleId, "userId", userId, "rows", players)).consume();
        }

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        index = 0;
        for (Object raw : listValue(first(battle, "items", "battleItems", "battle_items"))) {
            Map<String, Object> source = mapValue(raw);
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("id", stableId("item", userId, battleId, String.valueOf(index++)));
            x.put("ownerUserId", userId);
            x.put("gamePlayerId", text(source, "playerId", "player_id", "ownerPlayerId"));
            x.put("itemId", text(source, "itemId", "item_id"));
            x.put("itemName", text(source, "itemName", "item_name", "name"));
            x.put("pictureUrl", text(source, "pictureUrl", "pic", "image"));
            x.put("grade", intValue(source, 0, "grade", "quality"));
            x.put("quantity", Math.max(1, intValue(source, 1, "quantity", "num", "count")));
            x.put("unitPrice", longValue(source, 0, "unitPrice", "price", "value"));
            items.add(x);
        }
        if (!items.isEmpty()) {
            tx.run("MATCH (b:Battle {id:$battleId,ownerUserId:$userId}) UNWIND $rows AS row " +
                            "CREATE (x:BattleItem {id:row.id}) SET x += row CREATE (b)-[:HAS_ITEM]->(x)",
                    params("battleId", battleId, "userId", userId, "rows", items)).consume();
        }
    }

    private List<Object> playerRows(Map<String, Object> battle) {
        List<Object> players = listValue(first(battle, "players", "teammates", "battlePlayers", "battle_players"));
        if (players.isEmpty()) {
            Map<String, Object> self = mapValue(first(battle, "self", "selfPlayer"));
            if (!self.isEmpty()) players = Collections.<Object>singletonList(self);
        }
        return players;
    }

    private Map<String, Object> battleProperties(String userId, String accountId, String id, String roomId,
                                                  Map<String, Object> b) {
        Map<String, Object> x = new LinkedHashMap<String, Object>();
        x.put("id", id); x.put("ownerUserId", userId); x.put("accountId", accountId); x.put("roomId", roomId);
        x.put("mapId", text(b, "mapId", "map_id")); x.put("mapName", text(b, "mapName", "map_name"));
        x.put("eventTime", normalizeTime(first(b, "eventTime", "event_time")));
        x.put("startTime", normalizeTime(first(b, "startTime", "start_time")));
        x.put("durationSeconds", intValue(b, 0, "durationSeconds", "duration_s", "duration"));
        x.put("gameResult", intValue(b, 0, "gameResult", "game_result", "result"));
        x.put("gameRule", intValue(b, 0, "gameRule", "game_rule"));
        x.put("roleId", text(b, "roleId", "role_id")); x.put("roleName", text(b, "roleName", "role_name"));
        x.put("killCount", intValue(b, 0, "killCount", "kill_cnt", "kills"));
        x.put("playerKillCount", intValue(b, 0, "playerKillCount", "kill_player", "playerKills"));
        x.put("assistCount", intValue(b, 0, "assistCount", "assist_cnt", "assists"));
        x.put("rescueCount", intValue(b, 0, "rescueCount", "rescue", "rescues"));
        x.put("collectionValue", longValue(b, 0, "collectionValue", "collection_price"));
        x.put("gainedValue", longValue(b, 0, "gainedValue", "gained_price"));
        x.put("profitLoss", longValue(b, 0, "profitLoss", "profit_loss", "profit"));
        x.put("equipmentValue", longValue(b, 0, "equipmentValue", "original_equipment_price"));
        x.put("ranked", boolValue(b, "ranked", "is_rank_match")); x.put("leftEarly", boolValue(b, "leftEarly", "is_leave"));
        x.put("blueBox", boolValue(b, "blueBox", "has_blue_box")); x.put("queue", text(b, "queue", "queueName"));
        x.put("fetchedAt", now());
        return x;
    }

    private Map<String, Object> resolveAccount(String userId, String requestedId) {
        if (StringUtils.hasText(requestedId)) return requireAccount(userId, requestedId);
        List<Map<String, Object>> list = accounts(userId);
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> requireAccount(String userId, String accountId) {
        requireUserId(userId);
        if (!StringUtils.hasText(accountId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "游戏账号不存在");
        try (Session session = session()) {
            List<Map<String, Object>> rows = session.readTransaction(tx -> queryMaps(tx,
                    "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(a:GameAccount {id:$accountId,ownerUserId:$userId}) RETURN a{.*} AS value",
                    params("userId", userId, "accountId", accountId), "value", "ownerUserId"));
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "游戏账号不存在");
            return rows.get(0);
        }
    }

    private Map<String, Object> requireBattle(String userId, String battleId) {
        requireUserId(userId);
        try (Session session = session()) {
            List<Map<String, Object>> rows = session.readTransaction(tx -> queryMaps(tx,
                    "MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->(:GameAccount {ownerUserId:$userId})" +
                            "-[:HAS_BATTLE]->(b:Battle {id:$battleId,ownerUserId:$userId}) RETURN b{.*} AS value",
                    params("userId", userId, "battleId", battleId), "value", "ownerUserId"));
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "战绩不存在");
            return rows.get(0);
        }
    }

    private List<Map<String, Object>> filteredBattles(String userId, String requestedAccountId, String start, String end) {
        Map<String, Object> account = resolveAccount(userId, requestedAccountId);
        if (account == null) return Collections.emptyList();
        StringBuilder query = new StringBuilder("MATCH (:StatsUser {userId:$userId})-[:OWNS_ACCOUNT]->" +
                "(:GameAccount {id:$accountId,ownerUserId:$userId})-[:HAS_BATTLE]->(b:Battle {ownerUserId:$userId}) WHERE true");
        Map<String, Object> p = params("userId", userId, "accountId", account.get("id"));
        if (StringUtils.hasText(start)) { query.append(" AND b.eventTime >= $start"); p.put("start", normalizeBoundary(start, false)); }
        if (StringUtils.hasText(end)) { query.append(" AND b.eventTime <= $end"); p.put("end", normalizeBoundary(end, true)); }
        query.append(" RETURN b{.*} AS value ORDER BY b.eventTime ASC");
        try (Session session = session()) {
            return session.readTransaction(tx -> queryMaps(tx, query.toString(), p, "value", "ownerUserId"));
        }
    }

    private void addFilters(StringBuilder where, Map<String, Object> p, String mapName, String result,
                            String start, String end) {
        if (StringUtils.hasText(mapName)) { where.append(" AND b.mapName=$map"); p.put("map", mapName); }
        if (StringUtils.hasText(result)) {
            int parsed;
            try { parsed = Integer.parseInt(result); } catch (NumberFormatException ex) { throw badRequest("result 参数无效"); }
            where.append(parsed == 0 ? " AND b.gameResult=0" : " AND b.gameResult<>0");
        }
        if (StringUtils.hasText(start)) { where.append(" AND b.eventTime >= $start"); p.put("start", normalizeBoundary(start, false)); }
        if (StringUtils.hasText(end)) { where.append(" AND b.eventTime <= $end"); p.put("end", normalizeBoundary(end, true)); }
    }

    private Session session() {
        initializer.ensureReady();
        return driver.session(SessionConfig.forDatabase(initializer.database()));
    }

    private List<Map<String, Object>> queryMaps(Transaction tx, String query, Map<String, Object> params,
                                                String column, String... remove) {
        Result result = tx.run(query, params);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        while (result.hasNext()) {
            Record record = result.next();
            Map<String, Object> row = new LinkedHashMap<String, Object>(record.get(column).asMap());
            for (String key : remove) row.remove(key);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> emptySummary() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("totalBattles", 0); out.put("escapedBattles", 0); out.put("escapeRate", 0);
        out.put("playerKills", 0); out.put("totalProfit", 0); out.put("averageProfit", 0);
        out.put("averageDurationSeconds", 0); out.put("itemCount", 0); out.put("activeAccount", null);
        return out;
    }

    private static class Aggregate { long count; long escaped; long kills; long profit; long duration; }

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
        return new ArrayList<Map<String, Object>>(copy.subList(0, Math.min(5, copy.size())));
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
        if (!StringUtils.hasText(value)) return LocalDate.ofEpochDay(0);
        try { return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate(); } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(value.substring(0, Math.min(10, value.length()))); } catch (Exception ignored) { return LocalDate.ofEpochDay(0); }
    }

    private String normalizeBoundary(String value, boolean end) {
        if (!StringUtils.hasText(value)) return value;
        String v = value.trim();
        if (v.length() == 10) return v + (end ? "T23:59:59Z" : "T00:00:00Z");
        return normalizeTime(v);
    }

    private String normalizeTime(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) return "";
        try { return Instant.parse(text).toString(); } catch (DateTimeParseException ignored) { }
        if (text.length() >= 19 && text.charAt(10) == ' ') return text.substring(0, 10) + "T" + text.substring(11, 19) + "Z";
        return text;
    }

    private String stableId(String... values) {
        StringBuilder raw = new StringBuilder();
        for (String value : values) raw.append(value == null ? "" : value).append('\u0000');
        return UUID.nameUUIDFromBytes(raw.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> out = new HashMap<String, Object>();
        for (int i = 0; i + 1 < values.length; i += 2) out.put(String.valueOf(values[i]), values[i + 1]);
        return out;
    }

    private Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        return null;
    }

    private String text(Map<String, Object> map, String... keys) {
        Object value = first(map, keys); return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : Collections.emptyMap();
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List) return new ArrayList<Object>((List<?>) value);
        return Collections.emptyList();
    }

    private int intValue(Map<String, Object> map, int fallback, String... keys) {
        Object value = first(map, keys); return value == null ? fallback : number(value).intValue();
    }

    private long longValue(Map<String, Object> map, long fallback, String... keys) {
        Object value = first(map, keys); return value == null ? fallback : number(value).longValue();
    }

    private boolean boolValue(Map<String, Object> map, String... keys) { return bool(first(map, keys)); }

    private boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value != null && Arrays.asList("true", "1", "yes", "y").contains(String.valueOf(value).toLowerCase(Locale.ROOT));
    }

    private static Number number(Object value) {
        if (value instanceof Number) return (Number) value;
        try { return String.valueOf(value).contains(".") ? Double.parseDouble(String.valueOf(value)) : Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return 0L; }
    }

    private String label(Object value, String fallback) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    private double round(double value) { return Math.round(value * 100d) / 100d; }
    private String now() { return Instant.now().toString(); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
