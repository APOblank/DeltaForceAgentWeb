package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.ItemPrice;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Provides the graph-backed data used by the interactive gun modification table. */
@Service
public class GunModifierService {

    private static final Pattern PRICE_KEY_NOISE = Pattern.compile("[\\s_\\-/()（）]+", Pattern.UNICODE_CASE);
    private final Driver driver;
    private final AppProperties properties;
    private final MarketDataService marketDataService;

    public GunModifierService(Driver driver, AppProperties properties, MarketDataService marketDataService) {
        this.driver = driver;
        this.properties = properties;
        this.marketDataService = marketDataService;
    }

    public List<Map<String, Object>> firearms() {
        Map<String, ItemPrice> prices = pricesFor("firearm");
        String cypher = "MATCH (g:`枪械`) "
                + "OPTIONAL MATCH (g)-[:`拥有配件槽`]->(slot:`枪械配件槽`) "
                + "RETURN g.nodeId AS id, coalesce(g.name, g.`名称`, g.id, g.nodeId) AS name, "
                + "properties(g) AS props, count(slot) AS slotCount "
                + "ORDER BY slotCount DESC, name";
        try (Session session = session()) {
            Result result = session.run(cypher);
            List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
            while (result.hasNext()) {
                Record row = result.next();
                String name = row.get("name").isNull() ? "" : row.get("name").asString();
                Map<String, Object> gun = new LinkedHashMap<String, Object>();
                gun.put("id", row.get("id").isNull() ? name : row.get("id").asString());
                gun.put("name", name);
                gun.put("slotCount", row.get("slotCount").asLong());
                gun.put("props", row.get("props").asMap());
                gun.put("price", pricePayload(prices.get(priceKey(name))));
                output.add(gun);
            }
            return output;
        }
    }

    public Map<String, Object> firearmDetail(String firearmId) {
        String cypher = "MATCH (g:`枪械` {nodeId: $firearmId}) "
                + "OPTIONAL MATCH (g)-[:`拥有配件槽`]->(slot:`枪械配件槽`) "
                + "OPTIONAL MATCH (slot)-[:`允许安装`]->(specific:`配件`) "
                + "OPTIONAL MATCH (slot)-[:`槽位类型`]->(:`配件槽`)-[:`通用允许安装`]->(global:`配件`) "
                + "WITH g, slot, collect(DISTINCT specific) + collect(DISTINCT global) AS attachments "
                + "OPTIONAL MATCH (disabledBy:`配件`)-[:`被配件禁用`]->(slot) "
                + "RETURN properties(g) AS gunProps, slot.nodeId AS slotId, properties(slot) AS slotProps, "
                + "[a IN attachments WHERE a IS NOT NULL | properties(a)] AS attachmentProps, "
                + "collect(DISTINCT disabledBy.nodeId) AS disabledByIds "
                + "ORDER BY slotId";
        List<Record> rows;
        try (Session session = session()) {
            rows = session.run(cypher, Values.parameters("firearmId", firearmId)).list();
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到该枪械或枪械没有配件数据");
        }

        Map<String, ItemPrice> firearmPrices = pricesFor("firearm");
        Map<String, ItemPrice> attachmentPrices = pricesFor("attachment");
        Map<String, Object> gunProps = rows.get(0).get("gunProps").asMap();
        String gunName = stringValue(firstPresent(gunProps, "name", "名称", "id", "nodeId"));
        List<Map<String, Object>> slots = new ArrayList<Map<String, Object>>();
        Set<String> seenSlots = new HashSet<String>();
        for (Record row : rows) {
            if (row.get("slotId").isNull()) {
                continue;
            }
            String slotId = row.get("slotId").asString();
            if (!seenSlots.add(slotId)) {
                continue;
            }
            Map<String, Object> slotProps = row.get("slotProps").isNull()
                    ? Collections.<String, Object>emptyMap() : row.get("slotProps").asMap();
            Map<String, Object> slot = new LinkedHashMap<String, Object>();
            slot.put("id", slotId);
            slot.put("name", stringValue(firstPresent(slotProps, "槽位名称", "name", "名称", "id", "nodeId")));
            Object defaultAvailable = firstPresent(slotProps, "默认可用");
            slot.put("defaultAvailable", !(defaultAvailable instanceof Boolean) || ((Boolean) defaultAvailable));
            slot.put("parentAttachment", stringValue(firstPresent(slotProps, "父配件")));
            slot.put("parentSlot", stringValue(firstPresent(slotProps, "父槽位名称")));
            slot.put("props", slotProps);
            slot.put("disabledBy", row.get("disabledByIds").isNull() ? Collections.emptyList() : row.get("disabledByIds").asList());
            slot.put("attachments", attachments(row.get("attachmentProps").isNull()
                    ? Collections.emptyList() : row.get("attachmentProps").asList(), attachmentPrices));
            slots.add(slot);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", firearmId);
        result.put("name", gunName);
        result.put("props", gunProps);
        result.put("price", pricePayload(firearmPrices.get(priceKey(gunName))));
        result.put("baseStats", baseStats(gunProps));
        result.put("statFields", statFields());
        result.put("slots", slots);
        return result;
    }

    private List<Map<String, Object>> attachments(List<Object> propRows, Map<String, ItemPrice> prices) {
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        Set<String> seen = new HashSet<String>();
        for (Object raw : propRows) {
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) raw;
            String name = stringValue(firstPresent(props, "name", "名称", "id", "nodeId"));
            String id = stringValue(firstPresent(props, "nodeId", "id", "name", "名称"));
            if (name.isEmpty() || !seen.add(id)) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", id);
            item.put("name", name);
            item.put("props", props);
            item.put("price", pricePayload(prices.get(priceKey(name))));
            item.put("rawEffect", stringValue(firstPresent(props, "效果")));
            output.add(item);
        }
        Collections.sort(output, Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return output;
    }

    private Map<String, Object> baseStats(Map<String, Object> props) {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        for (String field : statFields()) {
            if (props.containsKey(field) && props.get(field) != null) stats.put(field, props.get(field));
        }
        return stats;
    }

    private List<String> statFields() {
        List<String> fields = new ArrayList<String>();
        Collections.addAll(fields, "后坐力控制", "操控速度", "据枪稳定性", "腰际射击精度", "枪口初速", "优势射程", "射速", "弹匣容量", "重量", "基础伤害", "护甲伤害");
        return fields;
    }

    private Map<String, ItemPrice> pricesFor(String type) {
        Map<String, ItemPrice> result = new HashMap<String, ItemPrice>();
        try {
            for (ItemPrice item : marketDataService.search("", type, 1000)) result.put(priceKey(item.getName()), item);
        } catch (Exception ignored) {
            // Graph data remains usable while the independent real-time price service is restarting.
        }
        return result;
    }

    private Map<String, Object> pricePayload(ItemPrice price) {
        if (price == null) return null;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("current_price", price.getCurrentPrice());
        result.put("combat_price", price.getCombatPrice());
        return result;
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(properties.getNeo4j().getDatabase()));
    }

    private String priceKey(String value) {
        return PRICE_KEY_NOISE.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private Object firstPresent(Map<String, Object> props, String... keys) {
        for (String key : keys) if (props.containsKey(key) && props.get(key) != null) return props.get(key);
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
