package com.deltaforce.agentweb.controller;

import com.deltaforce.agentweb.config.JwtAuthenticationInterceptor;
import com.deltaforce.agentweb.service.AdminNeo4jService;
import com.deltaforce.agentweb.service.AdminService;
import com.deltaforce.agentweb.service.ChatHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final ChatHistoryService chatHistoryService;
    private final AdminNeo4jService adminNeo4jService;

    public AdminController(AdminService adminService, ChatHistoryService chatHistoryService,
                           AdminNeo4jService adminNeo4jService) {
        this.adminService = adminService;
        this.chatHistoryService = chatHistoryService;
        this.adminNeo4jService = adminNeo4jService;
    }

    @GetMapping("/users/{userId}/sessions")
    public List<Map<String, Object>> userSessions(@PathVariable String userId,
                                                   @RequestParam(defaultValue = "true") boolean includeArchived,
                                                   HttpServletRequest request) {
        requireAdmin(request);
        List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>(chatHistoryService.listSessions(userId, false));
        if (includeArchived) sessions.addAll(chatHistoryService.listSessions(userId, true));
        sessions.sort((left, right) -> String.valueOf(right.get("updatedAt")).compareTo(String.valueOf(left.get("updatedAt"))));
        return sessions;
    }

    @GetMapping("/users/{userId}/sessions/{sessionId}/messages")
    public List<Map<String, Object>> userMessages(@PathVariable String userId, @PathVariable String sessionId,
                                                   HttpServletRequest request) {
        requireAdmin(request);
        if (!chatHistoryService.ownsSession(userId, sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该用户会话");
        }
        return chatHistoryService.listMessages(userId, sessionId);
    }

    @GetMapping("/neo4j/databases")
    public Map<String, Object> neo4jDatabases(HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("databases", adminNeo4jService.databases());
        return response;
    }

    @PostMapping("/neo4j/query")
    @SuppressWarnings("unchecked")
    public Map<String, Object> neo4jQuery(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireAdmin(request);
        String database = string(body.get("database"));
        String query = string(body.get("query"));
        boolean write = Boolean.TRUE.equals(body.get("write"));
        int limit = number(body.get("limit"), 100);
        Object parameterValue = body.get("parameters");
        Map<String, Object> parameters = parameterValue instanceof Map
                ? (Map<String, Object>) parameterValue
                : Collections.<String, Object>emptyMap();
        try {
            return adminNeo4jService.execute(database, query, parameters, write, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthenticationInterceptor.AUTHENTICATED_USER_ID);
        if (userId == null || !StringUtils.hasText(String.valueOf(userId))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            adminService.requireAdmin(String.valueOf(userId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员账户可使用此功能", ex);
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
