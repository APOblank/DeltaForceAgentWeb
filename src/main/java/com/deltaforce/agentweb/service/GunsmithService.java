package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.model.GunsmithRequest;
import com.deltaforce.agentweb.model.ItemPrice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GunsmithService {

    private final MarketDataService marketDataService;

    public GunsmithService(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public Map<String, Object> recommend(GunsmithRequest request) {
        long budget = request.getBudget() <= 0 ? 130000 : request.getBudget();
        ItemPrice weapon = marketDataService.findBestWeapon(request.getWeapon());
        long weaponCost = weapon == null ? 0 : weapon.getCurrentPrice();
        long attachmentBudget = Math.max(0, budget - weaponCost);

        List<ItemPrice> attachments = marketDataService.search("", "attachment", 1000).stream()
                .filter(item -> item.getCurrentPrice() <= Math.max(attachmentBudget, budget))
                .sorted(Comparator.comparingLong(ItemPrice::getCombatPrice).reversed())
                .limit(5)
                .collect(Collectors.toList());

        long attachmentCost = attachments.stream().mapToLong(ItemPrice::getCurrentPrice).sum();
        List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
        for (ItemPrice attachment : attachments) {
            Map<String, Object> part = new HashMap<String, Object>();
            part.put("name", attachment.getName());
            part.put("slot", guessSlot(attachment.getName()));
            part.put("currentPrice", attachment.getCurrentPrice());
            part.put("combatPrice", attachment.getCombatPrice());
            part.put("reason", "按战备价值和预算优先级筛选，后续可替换为 Neo4j 配件约束与属性算法。");
            parts.add(part);
        }

        Map<String, Object> response = new HashMap<String, Object>();
        response.put("weapon", weapon == null ? request.getWeapon() : weapon.getName());
        response.put("budget", budget);
        response.put("estimatedCost", weaponCost + attachmentCost);
        response.put("range", request.getRange());
        response.put("preference", request.getPreference());
        response.put("score", parts.isEmpty() ? "B" : "A");
        response.put("parts", parts);
        response.put("summary", "当前方案基于本地 JSON 价格数据生成；接入完整配件属性后可进一步约束槽位、冲突和属性收益。");
        return response;
    }

    private String guessSlot(String name) {
        String value = name == null ? "" : name;
        if (value.contains("鏋") || value.toLowerCase().contains("barrel")) {
            return "枪管";
        }
        if (value.contains("鎶ゆ湪")) {
            return "护木";
        }
        if (value.contains("鏋墭")) {
            return "枪托";
        }
        if (value.contains("寮归")) {
            return "弹匣";
        }
        if (value.contains("琛ュ伩") || value.contains("鍣")) {
            return "枪口";
        }
        return "配件";
    }
}
