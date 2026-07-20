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
            return session.writeTransaction(tx -> {
                Record row = tx.run(
                        "MATCH (u:AgentUser {userId: $userId}) " +
                                "CREATE (s:AgentChatSession {sessionId: $sessionId, title: $title, createdAt: $now, updatedAt: $now}) " +
                                "CREATE (u)-[:OWNS_SESSION]->(s) " +
                                "RETURN s.sessionId AS sessionId, s.title AS title, s.createdAt AS createdAt, s.updatedAt AS updatedAt",
                        Values.parameters("userId", userId, "sessionId", sessionId, "title", safeTitle, "now", now)
                ).single();
                return sessionRow(row);
            });
        }
    }

    public List<Map<String, Object>> listSessions(String userId) {
        try (Session session = session()) {
            return session.readTransaction(tx -> {
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                org.neo4j.driver.Result result = tx.run(
                        "MATCH (:AgentUser {userId: $userId})-[:OWNS_SESSION]->(s:AgentChatSession) " +
                                "RETURN s.sessionId AS sessionId, s.title AS title, s.createdAt AS createdAt, s.updatedAt AS updatedAt " +
                                "ORDER BY s.updatedAt DESC LIMIT 100",
                        Values.parameters("userId", userId)
                );
                while (result.hasNext()) {
                    rows.add(sessionRow(result.next()));
                }
                return rows;
            });
        }
    }

    public List<Map<String, Object>> listMessages(String userId, String sessionId) {
        try (Session session = session()) {
            return session.readTransaction(tx -> {
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
            session.writeTransaction(tx -> {
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
