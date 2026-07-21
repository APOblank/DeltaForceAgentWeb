package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.time.Duration;

@Service
public class KnowledgeService {

    private static final Pattern WRITE_KEYWORDS = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|DROP|ALTER|CALL|LOAD\\s+CSV|CREATE\\s+INDEX|CREATE\\s+CONSTRAINT)\\b"
    );

    private final Driver driver;
    private final AppProperties properties;

    public KnowledgeService(Driver driver, AppProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("database", properties.getNeo4j().getDatabase());
        try (Session session = session()) {
            String value = session.executeRead(tx -> tx.run("RETURN 'ok' AS status").single().get("status").asString(), transactionConfig());
            result.put("ok", "ok".equals(value));
        } catch (Exception ex) {
            result.put("ok", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }

    public List<Map<String, Object>> search(final String keyword, final int limit) {
        final String safeKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        final int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);

        try (Session session = session()) {
            return session.executeRead(tx -> {
                    Result queryResult = tx.run(
                            "MATCH (n) " +
                                    "WITH n, labels(n) AS labels, properties(n) AS props " +
                                    "RETURN id(n) AS id, labels, props " +
                                    "LIMIT $scanLimit",
                            Values.parameters("scanLimit", safeKeyword.isEmpty() ? safeLimit : Math.max(safeLimit * 20, 500))
                    );
                    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                    while (queryResult.hasNext()) {
                        Map<String, Object> row = toRow(queryResult.next());
                        if (safeKeyword.isEmpty() || rowMatches(row, safeKeyword)) {
                            rows.add(row);
                        }
                        if (rows.size() >= safeLimit) {
                            break;
                        }
                    }
                    return rows;
            }, transactionConfig());
        }
    }

    public Map<String, Object> query(final String query, final int limit) {
        final String safeQuery = normalizeReadOnlyQuery(query);
        final int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        try (Session session = session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(safeQuery, Values.parameters("limit", safeLimit));
                List<String> keys = result.keys();
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                while (result.hasNext() && rows.size() < safeLimit) {
                    Record record = result.next();
                    Map<String, Object> row = new HashMap<String, Object>();
                    for (String key : keys) {
                        row.put(key, convertValue(record.get(key)));
                    }
                    rows.add(row);
                }
                Map<String, Object> response = new HashMap<String, Object>();
                response.put("database", properties.getNeo4j().getDatabase());
                response.put("keys", keys);
                response.put("rows", rows);
                response.put("count", rows.size());
                return response;
            }, transactionConfig());
        }
    }

    public Map<String, Object> neighborhood(final String keyword, final Long selectedCenterId, final int limit) {
        final String safeKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        final int safeLimit = limit <= 0 ? 30 : Math.min(limit, 100);
        try (Session session = session()) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> candidates = findCandidateNodes(tx, safeKeyword, 12);
                Long centerId = selectedCenterId != null
                        ? selectedCenterId
                        : (candidates.isEmpty() ? null : ((Number) candidates.get(0).get("id")).longValue());
                Map<String, Object> response = new HashMap<String, Object>();
                response.put("database", properties.getNeo4j().getDatabase());
                response.put("keyword", keyword);
                response.put("candidates", candidates);
                if (centerId == null) {
                    response.put("nodes", new ArrayList<Map<String, Object>>());
                    response.put("relationships", new ArrayList<Map<String, Object>>());
                    response.put("centerId", null);
                    return response;
                }

                Result result = tx.run(
                        "MATCH (center)-[r]-(other) " +
                                "WHERE id(center) = $centerId " +
                                "RETURN center, r, other " +
                                "LIMIT $limit",
                        Values.parameters("centerId", centerId, "limit", safeLimit)
                );
                Map<Long, Map<String, Object>> nodes = new HashMap<Long, Map<String, Object>>();
                List<Map<String, Object>> relationships = new ArrayList<Map<String, Object>>();
                while (result.hasNext()) {
                    Record record = result.next();
                    Map<String, Object> center = nodeToMap(record.get("center").asNode());
                    Map<String, Object> other = nodeToMap(record.get("other").asNode());
                    Map<String, Object> relationship = relationshipToMap(record.get("r").asRelationship());
                    nodes.put((Long) center.get("id"), center);
                    nodes.put((Long) other.get("id"), other);
                    relationships.add(relationship);
                }
                if (nodes.isEmpty()) {
                    Result centerOnly = tx.run(
                            "MATCH (center) WHERE id(center) = $centerId RETURN center",
                            Values.parameters("centerId", centerId)
                    );
                    if (centerOnly.hasNext()) {
                        Map<String, Object> center = nodeToMap(centerOnly.next().get("center").asNode());
                        nodes.put((Long) center.get("id"), center);
                    }
                }
                response.put("nodes", new ArrayList<Map<String, Object>>(nodes.values()));
                response.put("relationships", relationships);
                response.put("centerId", centerId);
                return response;
            }, transactionConfig());
        }
    }

    private Session session() {
        return driver.session(SessionConfig.builder()
                .withDatabase(properties.getNeo4j().getDatabase())
                .withDefaultAccessMode(AccessMode.READ)
                .build());
    }

    private TransactionConfig transactionConfig() {
        int seconds = Math.max(1, Math.min(15, properties.getSecurity().getKnowledgeQueryTimeoutSeconds()));
        return TransactionConfig.builder().withTimeout(Duration.ofSeconds(seconds)).build();
    }

    private Map<String, Object> toRow(Record record) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("id", record.get("id").asLong());
        row.put("labels", record.get("labels").asList());
        Value props = record.get("props");
        row.put("properties", props.asMap());
        return row;
    }

    private String normalizeReadOnlyQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Cypher 查询不能为空");
        }
        if (value.contains(";")) {
            throw new IllegalArgumentException("一次只能执行一条只读 Cypher 查询");
        }
        String normalized = value.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        if (WRITE_KEYWORDS.matcher(normalized).find()) {
            throw new IllegalArgumentException("仅允许执行只读查询，不能包含写入、删除、过程调用或导入语句");
        }
        String lower = normalized.toLowerCase(Locale.ROOT).trim();
        if (!(lower.startsWith("match ") || lower.startsWith("with ") || lower.startsWith("return ") || lower.startsWith("unwind "))) {
            throw new IllegalArgumentException("查询必须以 MATCH、WITH、RETURN 或 UNWIND 开头");
        }
        return value;
    }

    @SuppressWarnings("deprecation") // Preserve numeric graph IDs for the existing public API contract.
    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.asObject();
        if (raw instanceof Node) {
            Node node = (Node) raw;
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("identity", node.id());
            data.put("labels", iterableToList(node.labels()));
            data.put("properties", node.asMap());
            return data;
        }
        if (raw instanceof Relationship) {
            Relationship relationship = (Relationship) raw;
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("identity", relationship.id());
            data.put("type", relationship.type());
            data.put("start", relationship.startNodeId());
            data.put("end", relationship.endNodeId());
            data.put("properties", relationship.asMap());
            return data;
        }
        return raw;
    }

    private List<Map<String, Object>> findCandidateNodes(TransactionContext tx, String keyword, int limit) {
        Result result = tx.run(
                "MATCH (n) RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props LIMIT $scanLimit",
                Values.parameters("scanLimit", keyword.isEmpty() ? limit : 2000)
        );
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        while (result.hasNext()) {
            Record record = result.next();
            Map<String, Object> row = toRow(record);
            if (keyword.isEmpty() || rowMatches(row, keyword)) {
                Map<String, Object> candidate = new HashMap<String, Object>();
                Map<String, Object> properties = record.get("props").asMap();
                candidate.put("id", record.get("id").asLong());
                candidate.put("labels", row.get("labels"));
                candidate.put("properties", properties);
                candidate.put("caption", nodeCaption(properties, record.get("id").asLong()));
                candidates.add(candidate);
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }
        return candidates;
    }

    @SuppressWarnings("deprecation") // Preserve numeric graph IDs for the existing public API contract.
    private Map<String, Object> nodeToMap(Node node) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("id", node.id());
        data.put("labels", iterableToList(node.labels()));
        data.put("properties", node.asMap());
        data.put("caption", nodeCaption(node.asMap(), node.id()));
        return data;
    }

    @SuppressWarnings("deprecation") // Preserve numeric graph IDs for the existing public API contract.
    private Map<String, Object> relationshipToMap(Relationship relationship) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("id", relationship.id());
        data.put("type", relationship.type());
        data.put("source", relationship.startNodeId());
        data.put("target", relationship.endNodeId());
        data.put("properties", relationship.asMap());
        return data;
    }

    private String nodeCaption(Map<String, Object> properties, long id) {
        Object value = firstPresent(properties, "name", "title", "名称", "displayName", "label", "id");
        return value == null ? "#" + id : String.valueOf(value);
    }

    private Object firstPresent(Map<String, Object> properties, String... keys) {
        for (String key : keys) {
            if (properties.containsKey(key) && properties.get(key) != null) {
                return properties.get(key);
            }
        }
        return null;
    }

    private List<Object> iterableToList(Iterable<?> values) {
        List<Object> result = new ArrayList<Object>();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    private boolean rowMatches(Map<String, Object> row, String keyword) {
        return stringify(row.get("labels")).contains(keyword)
                || stringify(row.get("properties")).contains(keyword);
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map) {
            StringBuilder builder = new StringBuilder();
            for (Object entry : ((Map<?, ?>) value).entrySet()) {
                builder.append(' ').append(stringify(entry));
            }
            return builder.toString().toLowerCase(Locale.ROOT);
        }
        if (value instanceof Iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : (Iterable<?>) value) {
                builder.append(' ').append(stringify(item));
            }
            return builder.toString().toLowerCase(Locale.ROOT);
        }
        if (value.getClass().isArray()) {
            StringBuilder builder = new StringBuilder();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                builder.append(' ').append(stringify(java.lang.reflect.Array.get(value, index)));
            }
            return builder.toString().toLowerCase(Locale.ROOT);
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }
}
