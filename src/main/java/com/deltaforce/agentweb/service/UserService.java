package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.AuthResponse;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

@Service
public class UserService {

    private final Driver driver;
    private final AppProperties properties;

    public UserService(Driver driver, AppProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    @PostConstruct
    public void ensureConstraints() {
        ensureUserDatabase();
        try (Session session = session()) {
            session.writeTransaction(tx -> {
                tx.run("CREATE CONSTRAINT agent_user_id IF NOT EXISTS FOR (u:AgentUser) REQUIRE u.userId IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT agent_user_name IF NOT EXISTS FOR (u:AgentUser) REQUIRE u.username IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT agent_session_id IF NOT EXISTS FOR (s:AgentChatSession) REQUIRE s.sessionId IS UNIQUE").consume();
                tx.run("CREATE CONSTRAINT agent_message_id IF NOT EXISTS FOR (m:AgentChatMessage) REQUIRE m.messageId IS UNIQUE").consume();
                return null;
            });
        } catch (Exception ignored) {
            // Neo4j may be offline during Spring startup; request endpoints will surface connection errors.
        }
    }

    private void ensureUserDatabase() {
        String database = userDatabase();
        if (!database.matches("[a-z0-9.-]{3,63}")) {
            return;
        }
        try (Session session = driver.session(SessionConfig.forDatabase("system"))) {
            session.writeTransaction(tx -> {
                tx.run("CREATE DATABASE `" + database + "` IF NOT EXISTS WAIT").consume();
                return null;
            });
        } catch (Exception ignored) {
            // Some Neo4j editions or users cannot create databases automatically.
        }
    }

    public AuthResponse register(String username, String password) {
        String cleanName = normalize(username);
        String salt = UUID.randomUUID().toString().replace("-", "");
        String userId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String passwordHash = hashPassword(cleanName, password, salt);

        try (Session session = session()) {
            return session.writeTransaction(tx -> {
                Result existingResult = tx.run(
                        "MATCH (u:AgentUser {username: $username}) RETURN u LIMIT 1",
                        Values.parameters("username", cleanName)
                );
                if (existingResult.hasNext()) {
                    throw new IllegalArgumentException("用户名已存在");
                }
                Record row = tx.run(
                        "CREATE (u:AgentUser {" +
                                "userId: $userId, username: $username, displayName: $displayName, " +
                                "passwordHash: $passwordHash, salt: $salt, createdAt: $now, updatedAt: $now" +
                                "}) RETURN u.userId AS userId, u.username AS username, u.displayName AS displayName",
                        Values.parameters(
                                "userId", userId,
                                "username", cleanName,
                                "displayName", cleanName,
                                "passwordHash", passwordHash,
                                "salt", salt,
                                "now", now
                        )
                ).single();
                return toAuthResponse(row);
            });
        }
    }

    public AuthResponse login(String username, String password) {
        String cleanName = normalize(username);
        try (Session session = session()) {
            return session.writeTransaction(tx -> {
                Result result = tx.run(
                        "MATCH (u:AgentUser {username: $username}) " +
                                "RETURN u.userId AS userId, u.username AS username, u.displayName AS displayName, " +
                                "u.passwordHash AS passwordHash, u.salt AS salt LIMIT 1",
                        Values.parameters("username", cleanName)
                );
                if (!result.hasNext()) {
                    throw new IllegalArgumentException("用户名或密码错误");
                }
                Record row = result.next();
                String expected = row.get("passwordHash").asString("");
                String salt = row.get("salt").asString("");
                if (!expected.equals(hashPassword(cleanName, password, salt))) {
                    throw new IllegalArgumentException("用户名或密码错误");
                }
                tx.run(
                        "MATCH (u:AgentUser {userId: $userId}) SET u.lastLoginAt = $now, u.updatedAt = $now",
                        Values.parameters("userId", row.get("userId").asString(), "now", Instant.now().toString())
                ).consume();
                return toAuthResponse(row);
            });
        }
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(userDatabase()));
    }

    private String userDatabase() {
        String database = properties.getNeo4j().getUserDatabase();
        return StringUtils.hasText(database) ? database.trim() : "deltaforceusers";
    }

    private AuthResponse toAuthResponse(Record row) {
        return new AuthResponse(
                row.get("userId").asString(),
                row.get("username").asString(),
                row.get("displayName").asString()
        );
    }

    private String normalize(String username) {
        String value = username == null ? "" : username.trim();
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        return value;
    }

    private String hashPassword(String username, String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((username + ":" + salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash password", ex);
        }
    }
}
