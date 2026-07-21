package com.deltaforce.agentweb.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_BUCKETS = 20_000;
    private final AppProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitInterceptor(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        int limit = limitFor(path);
        String actor = actor(request);
        String category = category(path);
        long now = Instant.now().getEpochSecond();
        Window window = windows.compute(actor + ':' + category, (key, current) ->
                current == null || current.startedAt + WINDOW_SECONDS <= now
                        ? new Window(now, 1) : new Window(current.startedAt, current.count + 1));
        if (windows.size() > MAX_BUCKETS) {
            windows.entrySet().removeIf(entry -> entry.getValue().startedAt + WINDOW_SECONDS <= now);
        }
        if (window.count > Math.max(1, limit)) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, window.startedAt + WINDOW_SECONDS - now)));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        }
        return true;
    }

    private int limitFor(String path) {
        if (path.startsWith("/api/auth/")) return properties.getRateLimits().getAuthPerMinute();
        if (path.startsWith("/api/chat")) return properties.getRateLimits().getChatPerMinute();
        if (path.startsWith("/api/knowledge/")) return properties.getRateLimits().getKnowledgeQueryPerMinute();
        return properties.getRateLimits().getGeneralApiPerMinute();
    }

    private String category(String path) {
        if (path.startsWith("/api/auth/")) return "auth";
        if (path.startsWith("/api/chat")) return "chat";
        if (path.startsWith("/api/knowledge/")) return "knowledge";
        return "api";
    }

    private String actor(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthenticationInterceptor.AUTHENTICATED_USER_ID);
        return StringUtils.hasText(userId == null ? "" : String.valueOf(userId))
                ? "user:" + userId : "ip:" + request.getRemoteAddr();
    }

    private record Window(long startedAt, int count) {}
}
