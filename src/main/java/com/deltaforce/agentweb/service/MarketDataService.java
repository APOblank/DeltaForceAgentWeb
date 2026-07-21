package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.ItemPrice;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads market prices from the sjzdata Neo4j graph. The independent 8010 service
 * is intentionally retained only for the administrator-triggered refresh action.
 */
@Service
public class MarketDataService {

    private static final List<String> PRICE_PROPERTIES = Arrays.asList(
            "当前购买价格", "战备价值", "今日涨跌幅", "当前假账数值", "假账数值占比",
            "3日价格", "3日涨跌幅", "7日价格", "7日涨跌幅", "30日价格", "30日涨跌幅"
    );

    private final AppProperties properties;
    private final RestTemplate restTemplate;
    private final Driver driver;

    public MarketDataService(AppProperties properties, RestTemplate restTemplate, Driver driver) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.driver = driver;
    }

    public List<ItemPrice> search(String keyword, String type, int limit) {
        String safeKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String kind = toKind(type);
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);

        return fetchGraphItems().stream()
                .filter(item -> matchesKind(item.getType(), kind))
                .filter(item -> !StringUtils.hasText(safeKeyword)
                        || String.valueOf(item.getName()).toLowerCase(Locale.ROOT).contains(safeKeyword))
                .sorted(Comparator.comparingLong(ItemPrice::getCombatPrice).reversed())
                .limit(safeLimit)
                .collect(Collectors.toList());
    }

    public List<ItemPrice> all() {
        return search("", "", 1000);
    }

    public ItemPrice findBestWeapon(String weapon) {
        String keyword = weapon == null ? "" : weapon.trim();
        return search(keyword, "firearm", 20).stream().findFirst().orElse(null);
    }

    /** The sole remaining call to the independent real-time service. */
    public Map<String, Object> refresh(Map<String, Object> request) {
        URI uri = UriComponentsBuilder.fromHttpUrl(refreshBaseUrl() + "/refresh").build().toUri();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    uri, request == null ? new HashMap<String, Object>() : request, Map.class
            );
            return response == null ? new HashMap<String, Object>() : response;
        } catch (RestClientException ex) {
            throw new IllegalStateException("刷新市场数据失败: " + uri, ex);
        }
    }

    private List<ItemPrice> fetchGraphItems() {
        String cypher = "MATCH (n) "
                + "WHERE any(property IN $priceProperties WHERE n[property] IS NOT NULL) "
                + "RETURN labels(n) AS labels, properties(n) AS props";
        List<ItemPrice> output = new ArrayList<ItemPrice>();
        try (Session session = driver.session(SessionConfig.forDatabase(properties.getNeo4j().getDatabase()))) {
            List<Record> rows = session.readTransaction(tx -> tx.run(
                    cypher, Values.parameters("priceProperties", PRICE_PROPERTIES)
            ).list());
            for (Record row : rows) {
                Map<String, Object> props = row.get("props").asMap();
                String name = stringValue(firstPresent(props, "name", "名称", "nodeId", "id"));
                if (!StringUtils.hasText(name)) continue;
                Set<String> labels = new HashSet<String>(row.get("labels").asList(value -> value.asString()));
                String itemType = resolveType(labels, props);
                if (itemType == null) continue;

                ItemPrice item = new ItemPrice(
                        name,
                        itemType,
                        longValue(firstPresent(props, "当前购买价格", "current_price", "currentPrice")),
                        longValue(firstPresent(props, "战备价值", "combat_price", "combatPrice"))
                );
                item.setTodayChangePercent(doubleValue(firstPresent(props, "今日涨跌幅", "today_change_percent", "todayChangePercent")));
                item.setCurrentFakeAccount(longValue(firstPresent(props, "当前假账数值", "current_fake_account", "currentFakeAccount")));
                item.setFakeAccountRatioPercent(doubleValue(firstPresent(props, "假账数值占比", "fake_account_ratio_percent", "fakeAccountRatioPercent")));
                item.setPrice3d(longValue(firstPresent(props, "3日价格", "price_3d", "price3d")));
                item.setChange3dPercent(doubleValue(firstPresent(props, "3日涨跌幅", "change_3d_percent", "change3dPercent")));
                item.setPrice7d(longValue(firstPresent(props, "7日价格", "price_7d", "price7d")));
                item.setChange7dPercent(doubleValue(firstPresent(props, "7日涨跌幅", "change_7d_percent", "change7dPercent")));
                item.setPrice30d(longValue(firstPresent(props, "30日价格", "price_30d", "price30d")));
                item.setChange30dPercent(doubleValue(firstPresent(props, "30日涨跌幅", "change_30d_percent", "change30dPercent")));
                output.add(item);
            }
        }
        return output;
    }

    private String resolveType(Set<String> labels, Map<String, Object> props) {
        if (labels.contains("枪械") || labels.contains("武器")) return "firearm";
        if (labels.contains("配件")) return "attachment";

        String category = normalize(stringValue(firstPresent(props,
                "维修_装备类别", "类型", "类别", "装备类型", "分类", "type")));
        Set<String> normalizedLabels = new HashSet<String>();
        for (String label : labels) normalizedLabels.add(normalize(label));
        String descriptor = category + " " + String.join(" ", normalizedLabels);
        if (descriptor.contains("头盔") || descriptor.contains("helmet")) return "helmets";
        if (descriptor.contains("护甲") || descriptor.contains("防弹衣") || descriptor.contains("armor")) return "armor";
        if (descriptor.contains("胸挂") || descriptor.contains("胸挂") || descriptor.contains("chest") || descriptor.contains("rig")) return "chest_rigs";
        if (descriptor.contains("背包") || descriptor.contains("backpack")) return "backpacks";
        return labels.contains("装备") ? "equipment" : null;
    }

    private boolean matchesKind(String itemType, String kind) {
        if (!StringUtils.hasText(kind)) return true;
        return kind.equals(itemType);
    }

    private String toKind(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if ("firearm".equals(value) || "firearms".equals(value)) return "firearm";
        if ("attachment".equals(value) || "attachments".equals(value)) return "attachment";
        if ("armor".equals(value)) return "armor";
        if ("helmet".equals(value) || "helmets".equals(value)) return "helmets";
        if ("chest_rig".equals(value) || "chest_rigs".equals(value)) return "chest_rigs";
        if ("backpack".equals(value) || "backpacks".equals(value)) return "backpacks";
        return "";
    }

    private String refreshBaseUrl() {
        String value = properties.getRealtimeData().getBaseUrl();
        String trimmed = StringUtils.hasText(value) ? value.trim() : "http://127.0.0.1:8010";
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private Object firstPresent(Map<String, Object> props, String... keys) {
        for (String key : keys) if (props.containsKey(key) && props.get(key) != null) return props.get(key);
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Math.round(Double.parseDouble(String.valueOf(value).replace(",", "").trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return 0D;
        try {
            return Double.parseDouble(String.valueOf(value).replace("%", "").replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }
}
