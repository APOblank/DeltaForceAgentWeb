package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {

    private final Driver driver;
    private final AppProperties properties;

    public AdminService(Driver driver, AppProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    public void requireAdmin(String userId) {
        try (Session session = session()) {
            Record user = session.executeRead(tx -> {
                Result result = tx.run(
                        "MATCH (u:AgentUser {userId: $userId}) " +
                                "RETURN coalesce(u.role, 'USER') AS role, u.username AS username LIMIT 1",
                        Values.parameters("userId", userId));
                return result.hasNext() ? result.next() : null;
            });
            String role = user == null ? "" : user.get("role").asString("");
            String username = user == null ? "" : user.get("username").asString("");
            boolean allowed = isAdminIdentity(role, username);
            if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员账户可使用此功能");
            if (!UserService.ROLE_ADMIN.equalsIgnoreCase(role)) {
                session.executeWrite(tx -> {
                    tx.run("MATCH (u:AgentUser {userId: $userId}) " +
                                    "SET u.role = 'ADMIN', u.updatedAt = $now",
                            Values.parameters("userId", userId, "now", Instant.now().toString())).consume();
                    return null;
                });
            }
        }
    }

    static boolean isAdminIdentity(String role, String username) {
        return UserService.ROLE_ADMIN.equalsIgnoreCase(role)
                || "admin".equalsIgnoreCase(username);
    }

    public List<Map<String, Object>> users() {
        try (Session session = session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(
                        "MATCH (u:AgentUser) OPTIONAL MATCH (u)-[:OWNS_SESSION]->(s:AgentChatSession) "
                                + "RETURN u.userId AS userId, u.username AS username, u.displayName AS displayName, "
                                + "coalesce(u.role, 'USER') AS role, "
                                + "u.createdAt AS createdAt, u.updatedAt AS updatedAt, u.lastLoginAt AS lastLoginAt, count(s) AS sessionCount "
                                + "ORDER BY u.createdAt DESC"
                );
                List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
                while (result.hasNext()) {
                    Record row = result.next();
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("userId", row.get("userId").asString(""));
                    item.put("username", row.get("username").asString(""));
                    item.put("displayName", row.get("displayName").asString(""));
                    item.put("role", row.get("role").asString("USER"));
                    item.put("createdAt", row.get("createdAt").asString(""));
                    item.put("updatedAt", row.get("updatedAt").asString(""));
                    item.put("lastLoginAt", row.get("lastLoginAt").asString(""));
                    item.put("sessionCount", row.get("sessionCount").asLong());
                    output.add(item);
                }
                return output;
            });
        }
    }

    public Map<String, Object> dailyContent() {
        try (Session session = session()) {
            return session.executeRead(tx -> {
                Result result = tx.run("MATCH (c:AdminDailyContent {key: 'daily'}) RETURN properties(c) AS content LIMIT 1");
                if (result.hasNext()) return withDefaults(result.next().get("content").asMap());
                return withDefaults(new HashMap<String, Object>());
            });
        }
    }

    public boolean registrationEnabled() {
        return isRegistrationEnabled(dailyContent());
    }

    public Map<String, Object> setRegistrationEnabled(boolean enabled) {
        return saveDailyContent(Map.of("registrationEnabled", enabled));
    }

    public Map<String, Object> saveDailyContent(Map<String, Object> input) {
        Map<String, Object> merged = new HashMap<String, Object>(dailyContent());
        if (input != null) merged.putAll(input);
        final Map<String, Object> content = withDefaults(merged);
        content.put("key", "daily");
        content.put("updatedAt", Instant.now().toString());
        try (Session session = session()) {
            return session.executeWrite(tx -> {
                Record row = tx.run(
                        "MERGE (c:AdminDailyContent {key: 'daily'}) SET c += $content RETURN properties(c) AS content",
                        Values.parameters("content", content)
                ).single();
                return withDefaults(row.get("content").asMap());
            });
        }
    }

    private Map<String, Object> withDefaults(Map<String, Object> source) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("passwordAz3", string(source.get("passwordAz3"), "待发布"));
        data.put("passwordZeroDam", string(source.get("passwordZeroDam"), "待发布"));
        data.put("passwordSpaceCity", string(source.get("passwordSpaceCity"), "待发布"));
        data.put("passwordTidePrison", string(source.get("passwordTidePrison"), "待发布"));
        data.put("passwordBrakkesh", string(source.get("passwordBrakkesh"), "待发布"));
        data.put("passwordLongbow", string(source.get("passwordLongbow"), "待发布"));
        data.put("aiRecommendation", editable(source, "aiRecommendation", "今日 AI 改枪推荐待发布"));
        data.put("aiPrompt", editable(source, "aiPrompt", "在此填写用于生成今日改枪推荐的提示词。"));
        data.put("newsContent", editable(source, "newsContent", "更多三角洲今日资讯即将上线。"));
        data.put("githubUrl", editable(source, "githubUrl", ""));
        data.put("registrationEnabled", isRegistrationEnabled(source));
        data.put("updatedAt", string(source.get("updatedAt"), ""));
        return data;
    }

    static boolean isRegistrationEnabled(Map<String, Object> source) {
        Object value = source == null ? null : source.get("registrationEnabled");
        return value instanceof Boolean booleanValue
                ? booleanValue
                : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String string(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String editable(Map<String, Object> source, String key, String fallback) {
        if (!source.containsKey(key)) return fallback;
        return source.get(key) == null ? "" : String.valueOf(source.get(key)).trim();
    }

    private Session session() {
        String database = properties.getNeo4j().getUserDatabase();
        return driver.session(SessionConfig.forDatabase(database == null || database.trim().isEmpty() ? "deltaforceusers" : database.trim()));
    }
}
