package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.ItemPrice;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MarketDataService {

    private final AppProperties properties;
    private final RestTemplate restTemplate;

    public MarketDataService(AppProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public List<ItemPrice> search(String keyword, String type, int limit) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        String kind = toKind(type);
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);

        List<ItemPrice> items = fetchItems(kind, safeKeyword);
        String lowerKeyword = safeKeyword.toLowerCase(Locale.ROOT);
        return items.stream()
                .filter(item -> !StringUtils.hasText(lowerKeyword)
                        || String.valueOf(item.getName()).toLowerCase(Locale.ROOT).contains(lowerKeyword))
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

    public Map<String, Object> refresh(Map<String, Object> request) {
        URI uri = UriComponentsBuilder.fromHttpUrl(trimTrailingSlash(properties.getRealtimeData().getBaseUrl()) + "/refresh").build().toUri();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(uri, request == null ? new java.util.HashMap<String, Object>() : request, Map.class);
            return response == null ? new java.util.HashMap<String, Object>() : response;
        } catch (RestClientException ex) {
            throw new IllegalStateException("刷新市场数据失败: " + uri, ex);
        }
    }

    private List<ItemPrice> fetchItems(String kind, String keyword) {
        URI uri = buildItemsUri(kind, keyword);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            return parseItems(response);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to fetch market data from realtime API: " + uri, ex);
        }
    }

    private URI buildItemsUri(String kind, String keyword) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(trimTrailingSlash(properties.getRealtimeData().getBaseUrl()) + "/items");
        if (StringUtils.hasText(kind)) {
            builder.queryParam("kind", kind);
        }
        if (StringUtils.hasText(keyword)) {
            builder.queryParam("name", keyword);
        }
        return builder.build().encode().toUri();
    }

    private List<ItemPrice> parseItems(Map<String, Object> response) {
        List<ItemPrice> result = new ArrayList<ItemPrice>();
        if (response == null) {
            return result;
        }
        String kind = response.get("kind") == null ? "" : String.valueOf(response.get("kind"));
        Object itemsObject = response.get("items");
        if (!(itemsObject instanceof List)) {
            return result;
        }
        for (Object itemObject : (List<?>) itemsObject) {
            if (!(itemObject instanceof Map)) {
                continue;
            }
            Map<?, ?> item = (Map<?, ?>) itemObject;
            String name = stringValue(item.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            long currentPrice = longValue(item.get("current_price"));
            long combatPrice = longValue(item.get("combat_price"));
            ItemPrice price = new ItemPrice(name, fromKind(kind), currentPrice, combatPrice);
            price.setTodayChangePercent(doubleValue(item.get("today_change_percent")));
            price.setCurrentFakeAccount(longValue(item.get("current_fake_account")));
            price.setFakeAccountRatioPercent(doubleValue(item.get("fake_account_ratio_percent")));
            price.setPrice3d(longValue(item.get("price_3d")));
            price.setChange3dPercent(doubleValue(item.get("change_3d_percent")));
            price.setPrice7d(longValue(item.get("price_7d")));
            price.setChange7dPercent(doubleValue(item.get("change_7d_percent")));
            price.setPrice30d(longValue(item.get("price_30d")));
            price.setChange30dPercent(doubleValue(item.get("change_30d_percent")));
            result.add(price);
        }
        return result;
    }

    private String toKind(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if ("firearm".equals(value) || "firearms".equals(value)) {
            return "firearms";
        }
        if ("attachment".equals(value) || "attachments".equals(value)) {
            return "attachments";
        }
        if ("armor".equals(value)) {
            return "armor";
        }
        if ("helmet".equals(value) || "helmets".equals(value)) {
            return "helmets";
        }
        if ("chest_rig".equals(value) || "chest_rigs".equals(value)) {
            return "chest_rigs";
        }
        if ("backpack".equals(value) || "backpacks".equals(value)) {
            return "backpacks";
        }
        return "";
    }

    private String fromKind(String kind) {
        if ("firearms".equals(kind)) {
            return "firearm";
        }
        if ("attachments".equals(kind)) {
            return "attachment";
        }
        return StringUtils.hasText(kind) ? kind : "item";
    }

    private String trimTrailingSlash(String value) {
        String trimmed = StringUtils.hasText(value) ? value.trim() : "http://127.0.0.1:8010";
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
