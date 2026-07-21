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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

@Service
public class UserService {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final Driver driver;
    private final AppProperties properties;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(Driver driver, AppProperties properties, JwtService jwtService) {
        this.driver = driver;
        this.properties = properties;
        this.jwtService = jwtService;
        int configuredStrength = properties.getSecurity().getBcryptStrength();
        this.passwordEncoder = new BCryptPasswordEncoder(Math.max(10, Math.min(15, configuredStrength)));
    }

    @PostConstruct
    public void ensureConstraints() {
        ensureUserDatabase();
        try (Session session = session()) {
            session.executeWrite(tx -> {
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
            session.executeWrite(tx -> {
                tx.run("CREATE DATABASE `" + database + "` IF NOT EXISTS WAIT").consume();
                return null;
            });
        } catch (Exception ignored) {
            // Some Neo4j editions or users cannot create databases automatically.
        }
    }

    public AuthResponse register(String username, String password) {
        String cleanName = normalize(username);
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须为 10 到 128 个字符");
        }
        if (isReservedAdminName(cleanName)) {
            throw new IllegalArgumentException("该用户名不可注册");
        }
        String userId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String passwordHash = passwordEncoder.encode(password);

        try (Session session = session()) {
            return session.executeWrite(tx -> {
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
                                "passwordHash: $passwordHash, passwordAlgorithm: 'bcrypt', role: $role, " +
                                "createdAt: $now, updatedAt: $now" +
                                "}) RETURN u.userId AS userId, u.username AS username, u.displayName AS displayName, u.role AS role",
                        Values.parameters(
                                "userId", userId,
                                "username", cleanName,
                                "displayName", cleanName,
                                "passwordHash", passwordHash,
                                "role", ROLE_USER,
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
            return session.executeWrite(tx -> {
                Result result = tx.run(
                        "MATCH (u:AgentUser {username: $username}) " +
                                "RETURN u.userId AS userId, u.username AS username, u.displayName AS displayName, " +
                                "u.passwordHash AS passwordHash, u.salt AS salt, " +
                                "coalesce(u.passwordAlgorithm, 'sha256') AS passwordAlgorithm, " +
                                "coalesce(u.role, 'USER') AS role LIMIT 1",
                        Values.parameters("username", cleanName)
                );
                if (!result.hasNext()) {
                    throw new IllegalArgumentException("用户名或密码错误");
                }
                Record row = result.next();
                String expected = row.get("passwordHash").asString("");
                String salt = row.get("salt").asString("");
                String algorithm = row.get("passwordAlgorithm").asString("sha256");
                boolean valid = "bcrypt".equalsIgnoreCase(algorithm)
                        ? passwordEncoder.matches(password, expected)
                        : MessageDigest.isEqual(
                                expected.getBytes(StandardCharsets.US_ASCII),
                                legacyHash(cleanName, password, salt).getBytes(StandardCharsets.US_ASCII));
                if (!valid) {
                    throw new IllegalArgumentException("用户名或密码错误");
                }
                String upgradedHash = "bcrypt".equalsIgnoreCase(algorithm) ? expected : passwordEncoder.encode(password);
                tx.run("MATCH (u:AgentUser {userId: $userId}) " +
                                "SET u.lastLoginAt = $now, u.updatedAt = $now, " +
                                "u.passwordHash = $passwordHash, u.passwordAlgorithm = 'bcrypt', u.role = coalesce(u.role, 'USER') " +
                                "REMOVE u.salt",
                        Values.parameters("userId", row.get("userId").asString(), "now", Instant.now().toString(),
                                "passwordHash", upgradedHash)).consume();
                return toAuthResponse(row);
            });
        }
    }

    public void ensureBootstrapAdmin(String username, String password) {
        String cleanName = normalize(username);
        String now = Instant.now().toString();
        try (Session session = session()) {
            session.executeWrite(tx -> {
                Result existing = tx.run(
                        "MATCH (u:AgentUser {username: $username}) RETURN u.userId AS userId LIMIT 1",
                        Values.parameters("username", cleanName));
                if (existing.hasNext()) {
                    tx.run("MATCH (u:AgentUser {username: $username}) " +
                                    "SET u.role = 'ADMIN', u.updatedAt = $now, u.passwordHash = $passwordHash, " +
                                    "u.passwordAlgorithm = 'bcrypt' REMOVE u.salt",
                            Values.parameters("username", cleanName, "now", now,
                                    "passwordHash", passwordEncoder.encode(password))).consume();
                } else {
                    tx.run("CREATE (u:AgentUser {userId: $userId, username: $username, displayName: $displayName, " +
                                    "passwordHash: $passwordHash, passwordAlgorithm: 'bcrypt', role: 'ADMIN', " +
                                    "createdAt: $now, updatedAt: $now})",
                            Values.parameters("userId", UUID.randomUUID().toString(), "username", cleanName,
                                    "displayName", cleanName, "passwordHash", passwordEncoder.encode(password), "now", now)).consume();
                }
                return null;
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
        AuthResponse response = new AuthResponse(
                row.get("userId").asString(),
                row.get("username").asString(),
                row.get("displayName").asString(),
                row.get("role").asString(ROLE_USER)
        );
        response.setToken(jwtService.issue(response));
        response.setExpiresIn(properties.getSecurity().getJwtTtlSeconds());
        return response;
    }

    private String normalize(String username) {
        String value = username == null ? "" : username.trim();
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        return value;
    }

    private boolean isReservedAdminName(String username) {
        String configured = properties.getSecurity().getBootstrapAdminUsername();
        return "admin".equalsIgnoreCase(username)
                || (StringUtils.hasText(configured) && configured.trim().equalsIgnoreCase(username));
    }

    private String legacyHash(String username, String password, String salt) {
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
