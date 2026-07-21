package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.AuthResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public JwtService(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void validateConfiguration() {
        String secret = properties.getSecurity().getJwtSecret();
        String normalized = secret == null ? "" : secret.trim();
        String upper = normalized.toUpperCase();
        if (normalized.length() < 48 || upper.contains("CHANGE_ME") || upper.contains("CHANGEME")
                || upper.contains("PLACEHOLDER") || upper.contains("DEFAULT_SECRET")) {
            throw new IllegalStateException("delta-force.security.jwt-secret 必须是至少 48 位的随机密钥，且不能使用占位值");
        }
        if (!StringUtils.hasText(properties.getSecurity().getJwtIssuer())
                || !StringUtils.hasText(properties.getSecurity().getJwtAudience())) {
            throw new IllegalStateException("JWT issuer 和 audience 必须配置");
        }
    }

    public String issue(AuthResponse user) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        claims.put("sub", user.getUserId());
        claims.put("username", user.getUsername());
        claims.put("role", user.getRole());
        claims.put("iss", properties.getSecurity().getJwtIssuer());
        claims.put("aud", properties.getSecurity().getJwtAudience());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now);
        claims.put("exp", now + Math.max(60, Math.min(86400, properties.getSecurity().getJwtTtlSeconds())));
        try {
            String unsigned = encodeJson(header) + "." + encodeJson(claims);
            return unsigned + "." + sign(unsigned);
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发登录令牌", ex);
        }
    }

    public String authenticatedUserId(String authorization) {
        return authenticate(authorization).userId();
    }

    public Principal authenticate(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw unauthorized();
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length != 3) throw unauthorized();
        try {
            Map<String, Object> header = objectMapper.readValue(decode(parts[0]), new TypeReference<Map<String, Object>>() {});
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) throw unauthorized();
            byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] actual = parts[2].getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, actual)) throw unauthorized();
            Map<String, Object> claims = objectMapper.readValue(decode(parts[1]), new TypeReference<Map<String, Object>>() {});
            Object exp = claims.get("exp");
            long expiration = exp instanceof Number ? ((Number) exp).longValue() : Long.parseLong(String.valueOf(exp));
            String userId = String.valueOf(claims.get("sub"));
            String issuer = String.valueOf(claims.get("iss"));
            String audience = String.valueOf(claims.get("aud"));
            String role = String.valueOf(claims.getOrDefault("role", UserService.ROLE_USER));
            if (expiration <= Instant.now().getEpochSecond() || !StringUtils.hasText(userId) || "null".equals(userId)
                    || !properties.getSecurity().getJwtIssuer().equals(issuer)
                    || !properties.getSecurity().getJwtAudience().equals(audience)
                    || !(UserService.ROLE_ADMIN.equals(role) || UserService.ROLE_USER.equals(role))) throw unauthorized();
            return new Principal(userId, role);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unauthorized();
        }
    }

    private String encodeJson(Map<String, Object> value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private String sign(String value) throws Exception {
        String secret = properties.getSecurity().getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.trim().length() < 48) {
            throw new IllegalStateException("delta-force.security.jwt-secret 必须配置为至少 48 位的随机密钥");
        }
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期或无效，请重新登录");
    }

    public record Principal(String userId, String role) {}
}
