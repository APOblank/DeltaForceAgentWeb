package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatHistoryService {

    private final Driver driver;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(Driver driver, AppProperties properties, ObjectMapper objectMapper) {
        this.driver = driver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> createSession(String userId, String title) {
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "新的对话";
        try (Session session = session()) {
            return session.executeWrite(tx -> {
                Record row = tx.run(
                        "MATCH (u:AgentUser {userId: $userId}) " +
                                "CREATE (s:AgentChatSession {sessionId: $sessionId, title: $title, createdAt: $now, updatedAt: $now, archived: false}) " +
                                "CREATE (u)-[:OWNS_SESSION]->(s) " +
                                "RETURN s.sessionId AS sessionId, s.title AS title, s.createdAt AS createdAt, s.updatedAt AS updatedAt, s.archived AS archived",
                        Values.parameters("userId", userId, "sessionId", sessionId, "title", safeTitle, "now", now)
                ).single();
                return sessionRow(row);
            });
        }
    }

    public List<Map<String, Object>> listSessions(String userId, boolean archived) {
        try (Session session = session()) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                org.neo4j.driver.Result result = tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession) " +
                                "WHERE coalesce(s.archived, false) = $archived " +
                                "RETURN s.sessionId AS sessionId, s.title AS title, s.createdAt AS createdAt, s.updatedAt AS updatedAt, coalesce(s.archived, false) AS archived " +
                                "ORDER BY s.updatedAt DESC LIMIT 100",
                        Values.parameters("userId", userId, "archived", archived)
                );
                while (result.hasNext()) {
                    rows.add(sessionRow(result.next()));
                }
                return rows;
            });
        }
    }

    public boolean setSessionArchived(String userId, String sessionId, boolean archived) {
        try (Session session = session()) {
            return session.executeWrite(tx -> {
                org.neo4j.driver.Result result = tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId}) " +
                                "SET s.archived = $archived, s.updatedAt = $now " +
                                "RETURN s.sessionId AS sessionId",
                        Values.parameters("userId", userId, "sessionId", sessionId, "archived", archived, "now", Instant.now().toString())
                );
                return result.hasNext();
            });
        }
    }

    public boolean deleteSession(String userId, String sessionId) {
        try (Session session = session()) {
            return session.executeWrite(tx -> {
                org.neo4j.driver.Result result = tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId}) " +
                                "OPTIONAL MATCH (s)-[:HAS_MESSAGE]->(m:AgentChatMessage) " +
                                "OPTIONAL MATCH (s)-[:HAS_TASK]->(t:AgentTask) " +
                                "WITH s, [message IN collect(DISTINCT m) WHERE message IS NOT NULL] AS messages, [task IN collect(DISTINCT t) WHERE task IS NOT NULL] AS tasks " +
                                "FOREACH (message IN messages | DETACH DELETE message) " +
                                "FOREACH (task IN tasks | DETACH DELETE task) " +
                                "DETACH DELETE s " +
                                "RETURN 1 AS deleted",
                        Values.parameters("userId", userId, "sessionId", sessionId)
                );
                return result.hasNext();
            });
        }
    }

    public List<Map<String, Object>> listMessages(String userId, String sessionId) {
        try (Session session = session()) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                org.neo4j.driver.Result result = tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId}) " +
                                "OPTIONAL MATCH (s)-[:HAS_MESSAGE]->(m:AgentChatMessage) " +
                                "RETURN m.messageId AS messageId, m.role AS role, m.content AS content, " +
                                "m.createdAt AS createdAt, m.hasReact AS hasReact, m.reactEvents AS reactEvents " +
                                "ORDER BY m.createdAt ASC",
                        Values.parameters("userId", userId, "sessionId", sessionId)
                );
                while (result.hasNext()) {
                    Record row = result.next();
                    if (!row.get("messageId").isNull()) {
                        rows.add(messageRow(row));
                    }
                }
                return rows;
            });
        }
    }

    public String ensureSession(String userId, String sessionId, String firstMessage) {
        if (StringUtils.hasText(sessionId)) {
            if (!ownsSession(userId, sessionId)) {
                throw new IllegalArgumentException("当前用户无权访问该会话。");
            }
            return sessionId;
        }
        String title = titleFromMessage(firstMessage);
        return String.valueOf(createSession(userId, title).get("sessionId"));
    }

    public String appendMessage(String userId, String sessionId, String role, String content) {
        return appendMessage(userId, sessionId, role, content, null);
    }

    public String appendMessage(String userId, String sessionId, String role, String content, Object reactEvents) {
        String messageId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String reactEventsJson = toJson(reactEvents);
        boolean hasReact = StringUtils.hasText(reactEventsJson) && !"[]".equals(reactEventsJson);
        try (Session session = session()) {
            session.executeWrite(tx -> {
                tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId}) " +
                                "CREATE (m:AgentChatMessage {messageId: $messageId, role: $role, content: $content, " +
                                "createdAt: $now, hasReact: $hasReact, reactEvents: $reactEvents}) " +
                                "CREATE (s)-[:HAS_MESSAGE]->(m) " +
                                "SET s.updatedAt = $now",
                        Values.parameters(
                                "userId", userId,
                                "sessionId", sessionId,
                                "messageId", messageId,
                                "role", role,
                                "content", content,
                                "hasReact", hasReact,
                                "reactEvents", reactEventsJson,
                                "now", now
                        )
                ).consume();
                return null;
            });
        }
        return messageId;
    }

    public List<Map<String, Object>> listTasks(String userId, String sessionId) {
        try (Session session = session()) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();
                org.neo4j.driver.Result result = tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId}) " +
                                "OPTIONAL MATCH (s)-[:HAS_TASK]->(t:AgentTask) " +
                                "RETURN properties(t) AS task ORDER BY coalesce(t.updatedAt, t.createdAt) DESC",
                        Values.parameters("userId", userId, "sessionId", sessionId)
                );
                while (result.hasNext()) {
                    Record row = result.next();
                    if (!row.get("task").isNull()) {
                        tasks.add(new HashMap<String, Object>(row.get("task").asMap()));
                    }
                }
                return tasks;
            });
        }
    }

    public boolean cancelTask(String userId, String sessionId, String taskId) {
        try (Session session = session()) {
            return session.executeWrite(tx -> tx.run(
                    "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId})-[:HAS_TASK]->(t:AgentTask) " +
                            "WHERE coalesce(t.taskId, t.task_id, t.id) = $taskId " +
                            "SET t.status = 'cancelled', t.cancelledAt = $now, t.updatedAt = $now " +
                            "RETURN coalesce(t.taskId, t.task_id, t.id) AS taskId",
                    Values.parameters("userId", userId, "sessionId", sessionId, "taskId", taskId, "now", Instant.now().toString())
            ).hasNext());
        }
    }

    public boolean ownsSession(String userId, String sessionId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
            return false;
        }
        try (Session session = session()) {
            return session.executeRead(tx -> tx.run(
                    "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession {sessionId: $sessionId}) RETURN s.sessionId AS sessionId",
                    Values.parameters("userId", userId, "sessionId", sessionId)
            ).hasNext());
        }
    }

    private Session session() {
        String database = properties.getNeo4j().getUserDatabase();
        return driver.session(SessionConfig.forDatabase(StringUtils.hasText(database) ? database.trim() : "deltaforceusers"));
    }

    private Map<String, Object> sessionRow(Record row) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("sessionId", row.get("sessionId").asString());
        data.put("title", row.get("title").asString());
        data.put("createdAt", row.get("createdAt").asString());
        data.put("updatedAt", row.get("updatedAt").asString());
        data.put("archived", !row.get("archived").isNull() && row.get("archived").asBoolean(false));
        return data;
    }

    private Map<String, Object> messageRow(Record row) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("messageId", row.get("messageId").asString());
        data.put("role", row.get("role").asString());
        data.put("content", row.get("content").asString());
        data.put("createdAt", row.get("createdAt").asString());
        boolean hasReact = !row.get("hasReact").isNull() && row.get("hasReact").asBoolean(false);
        data.put("hasReact", hasReact);
        data.put("reactEvents", parseJson(row.get("reactEvents").isNull() ? "" : row.get("reactEvents").asString("")));
        return data;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "";
        }
    }

    private Object parseJson(String value) {
        if (!StringUtils.hasText(value)) {
            return new ArrayList<Object>();
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ex) {
            return new ArrayList<Object>();
        }
    }

    private String titleFromMessage(String message) {
        String value = message == null ? "新的对话" : message.trim();
        if (value.length() > 22) {
            return value.substring(0, 22);
        }
        return StringUtils.hasText(value) ? value : "新的对话";
    }
}
