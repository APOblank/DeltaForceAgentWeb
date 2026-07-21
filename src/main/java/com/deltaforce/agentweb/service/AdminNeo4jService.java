package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminNeo4jService {

    private static final int MAX_LIMIT = 500;
    private static final int MAX_QUERY_LENGTH = 20_000;
    private static final List<String> BLOCKED_WRITE_PHRASES = Arrays.asList(
            "CALL DBMS", "CALL DB.", "LOAD CSV", "USING PERIODIC COMMIT",
            "CREATE DATABASE", "DROP DATABASE", "ALTER DATABASE", "START DATABASE", "STOP DATABASE",
            "CREATE USER", "ALTER USER", "DROP USER", "CREATE ROLE", "DROP ROLE", "GRANT ", "DENY ", "REVOKE "
    );

    private final Driver driver;
    private final AppProperties properties;

    public AdminNeo4jService(Driver driver, AppProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    public List<String> databases() {
        Set<String> values = new LinkedHashSet<String>();
        addDatabase(values, properties.getNeo4j().getDatabase());
        addDatabase(values, properties.getNeo4j().getUserDatabase());
        addDatabase(values, properties.getNeo4j().getStatsDatabase());
        return new ArrayList<String>(values);
    }

    public Map<String, Object> execute(String database, String query, Map<String, Object> parameters,
                                       boolean write, int requestedLimit) {
        String safeDatabase = requireAllowedDatabase(database);
        String safeQuery = validateQuery(query, write);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        Map<String, Object> safeParameters = parameters == null
                ? Collections.<String, Object>emptyMap()
                : new LinkedHashMap<String, Object>(parameters);

        try (Session session = driver.session(SessionConfig.forDatabase(safeDatabase))) {
            if (write) {
                return session.writeTransaction(tx -> executeInTransaction(tx, safeDatabase, safeQuery, safeParameters, true, limit));
            }
            return session.readTransaction(tx -> executeInTransaction(tx, safeDatabase, safeQuery, safeParameters, false, limit));
        }
    }

    private Map<String, Object> executeInTransaction(Transaction tx, String database, String query,
                                                      Map<String, Object> parameters, boolean write, int limit) {
        Result result = tx.run(query, parameters);
        List<String> columns = new ArrayList<String>(result.keys());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        while (result.hasNext()) {
            Record record = result.next();
            if (rows.size() >= limit) {
                truncated = true;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (String key : columns) {
                row.put(key, jsonValue(record.get(key).asObject()));
            }
            rows.add(row);
        }
        ResultSummary resultSummary = result.consume();
        SummaryCounters counters = resultSummary.counters();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("containsUpdates", counters.containsUpdates());
        summary.put("nodesCreated", counters.nodesCreated());
        summary.put("nodesDeleted", counters.nodesDeleted());
        summary.put("relationshipsCreated", counters.relationshipsCreated());
        summary.put("relationshipsDeleted", counters.relationshipsDeleted());
        summary.put("propertiesSet", counters.propertiesSet());
        summary.put("labelsAdded", counters.labelsAdded());
        summary.put("labelsRemoved", counters.labelsRemoved());

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("database", database);
        response.put("mode", write ? "write" : "read");
        response.put("columns", columns);
        response.put("rows", rows);
        response.put("rowCount", rows.size());
        response.put("truncated", truncated);
        response.put("summary", summary);
        return response;
    }

    private String requireAllowedDatabase(String database) {
        String requested = StringUtils.hasText(database) ? database.trim() : properties.getNeo4j().getDatabase();
        for (String allowed : databases()) {
            if (allowed.equals(requested)) return requested;
        }
        throw new IllegalArgumentException("只能访问应用配置的 Neo4j 数据库");
    }

    private String validateQuery(String query, boolean write) {
        if (!StringUtils.hasText(query)) throw new IllegalArgumentException("Cypher 查询不能为空");
        String value = query.trim();
        if (value.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("Cypher 查询过长");
        if (value.endsWith(";")) value = value.substring(0, value.length() - 1).trim();
        if (value.contains(";")) throw new IllegalArgumentException("每次只能执行一条 Cypher 语句");
        String normalized = value.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (write) {
            for (String blocked : BLOCKED_WRITE_PHRASES) {
                if (normalized.contains(blocked)) {
                    throw new IllegalArgumentException("该写入语句包含禁止的系统级操作: " + blocked.trim());
                }
            }
        }
        return value;
    }

    private Object jsonValue(Object value) {
        if (value == null) return null;
        if (value instanceof Node) {
            Node node = (Node) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("kind", "node");
            result.put("id", node.id());
            List<String> labels = new ArrayList<String>();
            node.labels().forEach(labels::add);
            result.put("labels", labels);
            result.put("properties", jsonValue(node.asMap()));
            return result;
        }
        if (value instanceof Relationship) {
            Relationship relationship = (Relationship) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("kind", "relationship");
            result.put("id", relationship.id());
            result.put("type", relationship.type());
            result.put("startNodeId", relationship.startNodeId());
            result.put("endNodeId", relationship.endNodeId());
            result.put("properties", jsonValue(relationship.asMap()));
            return result;
        }
        if (value instanceof Path) {
            List<Object> segments = new ArrayList<Object>();
            for (Path.Segment segment : (Path) value) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("start", jsonValue(segment.start()));
                item.put("relationship", jsonValue(segment.relationship()));
                item.put("end", jsonValue(segment.end()));
                segments.add(item);
            }
            return segments;
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                result.put(String.valueOf(entry.getKey()), jsonValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) result.add(jsonValue(item));
            return result;
        }
        if (value.getClass().isArray()) {
            return String.valueOf(value);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return String.valueOf(value);
    }

    private void addDatabase(Set<String> databases, String value) {
        if (StringUtils.hasText(value)) databases.add(value.trim());
    }
}
