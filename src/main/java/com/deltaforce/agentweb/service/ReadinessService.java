package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.model.ItemPrice;
import com.deltaforce.agentweb.model.ReadinessRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据实时行情 API 返回的战备价和市场价，按装备槽位组合出最低成本的凑战备方案。
 */
@Service
public class ReadinessService {

    private static final List<Slot> SLOTS = Arrays.asList(
            new Slot("primary", "主武器", true),
            new Slot("helmet", "头盔", true),
            new Slot("armor", "防弹衣", true),
            new Slot("rig", "胸挂", false),
            new Slot("backpack", "背包", false)
    );

    private final MarketDataService marketDataService;

    public ReadinessService(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public Map<String, Object> plan(ReadinessRequest request) {
        long target = request.getTargetCombat();
        int topK = request.getTopK() == null ? 3 : request.getTopK();
        List<Candidate> candidates = loadCandidates();
        Map<String, List<Candidate>> candidatesBySlot = groupBySlot(candidates);
        List<String> missingRequiredSlots = missingRequiredSlots(candidatesBySlot);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targetCombat", target);
        result.put("candidateCount", candidates.size());
        result.put("missingRequiredSlots", missingRequiredSlots);
        if (!missingRequiredSlots.isEmpty()) {
            result.put("plans", Collections.emptyList());
            result.put("message", "必选槽位缺少行情数据，暂时无法生成方案。");
            return result;
        }

        List<Plan> plans = selectPlans(groupedKnapsack(candidatesBySlot), target, topK);
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
        for (Plan plan : plans) {
            payload.add(toPayload(plan, target));
        }
        result.put("plans", payload);
        result.put("message", payload.isEmpty() ? "当前行情数据无法组合出可用方案。" : "");
        return result;
    }

    private List<Candidate> loadCandidates() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (String type : Arrays.asList("firearm", "helmets", "armor", "chest_rigs", "backpacks")) {
            for (ItemPrice item : marketDataService.search("", type, 1000)) {
                String slotKey = slotForType(item.getType());
                long combat = item.getCombatPrice();
                if (slotKey == null || combat <= 0 || item.getName() == null || item.getName().trim().isEmpty()) {
                    continue;
                }
                long marketPrice = item.getCurrentPrice() > 0 ? item.getCurrentPrice() : combat;
                candidates.add(new Candidate(item.getName(), slotKey, combat, marketPrice,
                        item.getCurrentPrice() > 0 ? "实时市场价" : "战备价估算"));
            }
        }
        return candidates;
    }

    private String slotForType(String type) {
        if ("firearm".equals(type) || "firearms".equals(type)) return "primary";
        if ("helmet".equals(type) || "helmets".equals(type)) return "helmet";
        if ("armor".equals(type)) return "armor";
        if ("chest_rig".equals(type) || "chest_rigs".equals(type)) return "rig";
        if ("backpack".equals(type) || "backpacks".equals(type)) return "backpack";
        return null;
    }

    private Map<String, List<Candidate>> groupBySlot(List<Candidate> candidates) {
        Map<String, List<Candidate>> result = new HashMap<String, List<Candidate>>();
        for (Slot slot : SLOTS) result.put(slot.key, new ArrayList<Candidate>());
        for (Candidate candidate : candidates) result.get(candidate.slotKey).add(candidate);
        return result;
    }

    private List<String> missingRequiredSlots(Map<String, List<Candidate>> candidatesBySlot) {
        List<String> result = new ArrayList<String>();
        for (Slot slot : SLOTS) {
            if (slot.required && candidatesBySlot.get(slot.key).isEmpty()) result.add(slot.name);
        }
        return result;
    }

    // 移植自参考插件的分组背包思路：同一累计战备值只保留总价最低的分支。
    private Map<Long, Plan> groupedKnapsack(Map<String, List<Candidate>> candidatesBySlot) {
        Map<Long, Plan> state = new HashMap<Long, Plan>();
        state.put(0L, new Plan());
        for (Slot slot : SLOTS) {
            Map<Long, Plan> next = new HashMap<Long, Plan>();
            List<Candidate> options = candidatesBySlot.get(slot.key);
            for (Plan current : state.values()) {
                if (!slot.required) merge(next, current);
                for (Candidate candidate : options) merge(next, current.add(candidate));
            }
            state = next;
        }
        return state;
    }

    private void merge(Map<Long, Plan> state, Plan candidate) {
        Plan existing = state.get(candidate.totalCombat);
        if (existing == null || candidate.totalCost < existing.totalCost) state.put(candidate.totalCombat, candidate);
    }

    private List<Plan> selectPlans(Map<Long, Plan> state, long target, int topK) {
        List<Plan> candidates = new ArrayList<Plan>(state.values());
        List<Plan> qualified = new ArrayList<Plan>();
        for (Plan plan : candidates) if (plan.totalCombat >= target) qualified.add(plan);
        List<Plan> ranked = qualified.isEmpty() ? candidates : qualified;
        if (qualified.isEmpty()) {
            Collections.sort(ranked, Comparator.comparingLong((Plan p) -> Math.abs(p.totalCombat - target)).thenComparingLong(p -> p.totalCost));
        } else {
            Collections.sort(ranked, Comparator.comparingLong((Plan p) -> p.totalCost).thenComparing((Plan p) -> p.totalCombat, Comparator.reverseOrder()));
        }
        return ranked.subList(0, Math.min(topK, ranked.size()));
    }

    private Map<String, Object> toPayload(Plan plan, long target) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("totalCombat", plan.totalCombat);
        result.put("totalCost", plan.totalCost);
        result.put("gap", plan.totalCombat - target);
        result.put("qualified", plan.totalCombat >= target);
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (Candidate candidate : plan.entries) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", candidate.name);
            entry.put("slot", slotName(candidate.slotKey));
            entry.put("combatPrice", candidate.combat);
            entry.put("currentPrice", candidate.price);
            entry.put("priceSource", candidate.priceSource);
            entries.add(entry);
        }
        result.put("entries", entries);
        return result;
    }

    private String slotName(String key) {
        for (Slot slot : SLOTS) if (slot.key.equals(key)) return slot.name;
        return key;
    }

    private static class Slot {
        private final String key;
        private final String name;
        private final boolean required;
        private Slot(String key, String name, boolean required) { this.key = key; this.name = name; this.required = required; }
    }

    private static class Candidate {
        private final String name;
        private final String slotKey;
        private final long combat;
        private final long price;
        private final String priceSource;
        private Candidate(String name, String slotKey, long combat, long price, String priceSource) {
            this.name = name; this.slotKey = slotKey; this.combat = combat; this.price = price; this.priceSource = priceSource;
        }
    }

    private static class Plan {
        private final long totalCombat;
        private final long totalCost;
        private final List<Candidate> entries;
        private Plan() { this(0L, 0L, Collections.<Candidate>emptyList()); }
        private Plan(long totalCombat, long totalCost, List<Candidate> entries) { this.totalCombat = totalCombat; this.totalCost = totalCost; this.entries = entries; }
        private Plan add(Candidate candidate) {
            List<Candidate> next = new ArrayList<Candidate>(entries);
            next.add(candidate);
            return new Plan(totalCombat + candidate.combat, totalCost + candidate.price, next);
        }
    }
}
