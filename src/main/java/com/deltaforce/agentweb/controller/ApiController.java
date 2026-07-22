package com.deltaforce.agentweb.controller;

import com.deltaforce.agentweb.model.AuthRequest;
import com.deltaforce.agentweb.model.AuthResponse;
import com.deltaforce.agentweb.model.ChatSessionRequest;
import com.deltaforce.agentweb.model.ChatRequest;
import com.deltaforce.agentweb.model.ChatResponse;
import com.deltaforce.agentweb.model.GunsmithRequest;
import com.deltaforce.agentweb.model.ItemPrice;
import com.deltaforce.agentweb.model.KnowledgeQueryRequest;
import com.deltaforce.agentweb.model.ReadinessRequest;
import com.deltaforce.agentweb.service.ChatHistoryService;
import com.deltaforce.agentweb.service.AdminService;
import com.deltaforce.agentweb.service.ChatProxyService;
import com.deltaforce.agentweb.service.GunsmithService;
import com.deltaforce.agentweb.service.GunModifierService;
import com.deltaforce.agentweb.service.KnowledgeService;
import com.deltaforce.agentweb.service.MarketDataService;
import com.deltaforce.agentweb.service.ReadinessService;
import com.deltaforce.agentweb.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.deltaforce.agentweb.config.CorrelationIdFilter;
import com.deltaforce.agentweb.config.JwtAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;

@Validated
@RestController
@RequestMapping("/api")
public class ApiController {

    private final MarketDataService marketDataService;
    private final ReadinessService readinessService;
    private final AdminService adminService;
    private final GunsmithService gunsmithService;
    private final GunModifierService gunModifierService;
    private final KnowledgeService knowledgeService;
    private final ChatProxyService chatProxyService;
    private final UserService userService;
    private final ChatHistoryService chatHistoryService;
    private final ObjectMapper objectMapper;
    private final ExecutorService chatStreamExecutor;

    public ApiController(
            MarketDataService marketDataService,
            ReadinessService readinessService,
            AdminService adminService,
            GunsmithService gunsmithService,
            GunModifierService gunModifierService,
            KnowledgeService knowledgeService,
            ChatProxyService chatProxyService,
            UserService userService,
            ChatHistoryService chatHistoryService,
            ObjectMapper objectMapper,
            @Qualifier("chatStreamExecutor") ExecutorService chatStreamExecutor
    ) {
        this.marketDataService = marketDataService;
        this.readinessService = readinessService;
        this.adminService = adminService;
        this.gunsmithService = gunsmithService;
        this.gunModifierService = gunModifierService;
        this.knowledgeService = knowledgeService;
        this.chatProxyService = chatProxyService;
        this.userService = userService;
        this.chatHistoryService = chatHistoryService;
        this.objectMapper = objectMapper;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("service", "DeltaForceAgentWeb");
        Map<String, Object> neo4j = knowledgeService.health();
        boolean ok = Boolean.TRUE.equals(neo4j.get("ok"));
        response.put("neo4j", ok ? "ok" : "unavailable");
        try {
            response.put("marketItems", marketDataService.all().size());
            response.put("marketData", "ok");
        } catch (Exception ex) {
            ok = false;
            response.put("marketItems", 0);
            response.put("marketData", "unavailable");
        }
        response.put("ok", ok);
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @GetMapping("/market/items")
    public List<ItemPrice> marketItems(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return marketDataService.search(keyword, type, limit);
    }

    @GetMapping("/daily-content")
    public Map<String, Object> dailyContent() {
        return adminService.dailyContent();
    }

    @GetMapping("/admin/users")
    public List<Map<String, Object>> adminUsers(HttpServletRequest request) {
        adminService.requireAdmin(requireUser(request));
        return adminService.users();
    }

    @GetMapping("/admin/daily-content")
    public Map<String, Object> adminDailyContent(HttpServletRequest request) {
        adminService.requireAdmin(requireUser(request));
        return adminService.dailyContent();
    }

    @PostMapping("/admin/daily-content")
    public Map<String, Object> saveAdminDailyContent(HttpServletRequest request, @RequestBody Map<String, Object> content) {
        adminService.requireAdmin(requireUser(request));
        return adminService.saveDailyContent(content);
    }

    @PostMapping("/admin/market/refresh")
    public Map<String, Object> refreshMarket(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        adminService.requireAdmin(requireUser(request));
        return marketDataService.refresh(body);
    }

    @PostMapping("/gunsmith/recommend")
    public Map<String, Object> recommend(@Valid @RequestBody GunsmithRequest request) {
        return gunsmithService.recommend(request);
    }

    @PostMapping("/readiness/plans")
    public Map<String, Object> readinessPlans(@Valid @RequestBody ReadinessRequest request) {
        return readinessService.plan(request);
    }

    @GetMapping("/gun-modifier/firearms")
    public List<Map<String, Object>> modifierFirearms() {
        return gunModifierService.firearms();
    }

    @GetMapping("/gun-modifier/firearms/{firearmId}")
    public Map<String, Object> modifierFirearm(@PathVariable String firearmId) {
        return gunModifierService.firearmDetail(firearmId);
    }

    @GetMapping("/knowledge/search")
    public List<Map<String, Object>> searchKnowledge(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request
    ) {
        requireUser(request);
        return knowledgeService.search(keyword, limit);
    }

    @GetMapping("/knowledge/neighborhood")
    public Map<String, Object> knowledgeNeighborhood(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long centerId,
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest request
    ) {
        requireUser(request);
        return knowledgeService.neighborhood(keyword, centerId, limit);
    }

    @PostMapping("/knowledge/query")
    public Map<String, Object> queryKnowledge(@Valid @RequestBody KnowledgeQueryRequest request, HttpServletRequest httpRequest) {
        requireUser(httpRequest);
        int limit = request.getLimit() == null ? 100 : request.getLimit();
        return knowledgeService.query(request.getQuery(), limit);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        String sessionId = null;
        String messageId = StringUtils.hasText(request.getMessageId()) ? request.getMessageId() : UUID.randomUUID().toString();
        String userId = requireUser(httpRequest);
        sessionId = chatHistoryService.ensureSession(userId, request.getSessionId(), request.getMessage());
        chatHistoryService.appendMessageOnce(userId, sessionId, messageId, "user", request.getMessage(), null);
        ChatResponse response = chatProxyService.chat(request.getMessage(), request.getDebug(), request.getReactEvents(),
                userId, sessionId, messageId, request.getConversationMode(), requestId(httpRequest));
        response.setSessionId(StringUtils.hasText(sessionId) ? sessionId : response.getSessionId());
        if (StringUtils.hasText(userId) && StringUtils.hasText(sessionId)) {
            String assistantMessageId = chatHistoryService.appendMessageOnce(userId, sessionId, messageId,
                    "assistant", response.getAnswer(), response.getReactEvents());
            response.setMessageId(assistantMessageId);
        }
        return response;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        httpResponse.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(135_000L);
        emitter.onTimeout(emitter::complete);
        String sessionId = null;
        String messageId = StringUtils.hasText(request.getMessageId()) ? request.getMessageId() : UUID.randomUUID().toString();
        String userId = requireUser(httpRequest);
        sessionId = chatHistoryService.ensureSession(userId, request.getSessionId(), request.getMessage());
        chatHistoryService.appendMessageOnce(userId, sessionId, messageId, "user", request.getMessage(), null);

        final String finalSessionId = sessionId;
        final String finalUserId = userId;
        final String finalRequestId = requestId(httpRequest);
        try {
            chatStreamExecutor.execute(() -> {
                try {
                Map<String, Object> sessionEvent = new HashMap<String, Object>();
                sessionEvent.put("sessionId", finalSessionId);
                emitter.send(SseEmitter.event().name("session").data(objectMapper.writeValueAsString(sessionEvent)));
                chatProxyService.streamChat(request.getMessage(), request.getDebug(), request.getReactEvents(),
                        finalUserId, finalSessionId, messageId, request.getConversationMode(), finalRequestId, emitter, (answer, reactEvents) -> {
                    if (StringUtils.hasText(finalUserId) && StringUtils.hasText(finalSessionId)) {
                        return chatHistoryService.appendMessageOnce(finalUserId, finalSessionId, messageId,
                                "assistant", answer, reactEvents);
                    }
                    return "";
                });
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前对话请求较多，请稍后重试");
        }
        return emitter;
    }

    @PostMapping("/auth/register")
    public AuthResponse register(@Valid @RequestBody AuthRequest request) {
        return userService.register(request.getUsername(), request.getPassword());
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request) {
        return userService.login(request.getUsername(), request.getPassword());
    }

    @GetMapping("/chat/sessions")
    public List<Map<String, Object>> chatSessions(HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean archived
    ) {
        return chatHistoryService.listSessions(requireUser(request), archived);
    }

    @PostMapping("/chat/sessions")
    public Map<String, Object> createChatSession(@Valid @RequestBody ChatSessionRequest request, HttpServletRequest httpRequest) {
        return chatHistoryService.createSession(requireUser(httpRequest), request.getTitle());
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<Map<String, Object>> chatMessages(
            @PathVariable String sessionId, HttpServletRequest request
    ) {
        return chatHistoryService.listMessages(requireUser(request), sessionId);
    }

    @GetMapping("/chat/sessions/{sessionId}/tasks")
    public List<Map<String, Object>> chatTasks(@PathVariable String sessionId, HttpServletRequest request) {
        return chatHistoryService.listTasks(requireUser(request), sessionId);
    }

    @PostMapping("/chat/sessions/{sessionId}/tasks/{taskId}/cancel")
    public Map<String, Object> cancelChatTask(@PathVariable String sessionId, @PathVariable String taskId, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("cancelled", chatHistoryService.cancelTask(requireUser(request), sessionId, taskId));
        return response;
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    public Map<String, Object> deleteChatSession(
            @PathVariable String sessionId, HttpServletRequest request
    ) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("deleted", chatHistoryService.deleteSession(requireUser(request), sessionId));
        return response;
    }

    @PatchMapping("/chat/sessions/{sessionId}/archive")
    public Map<String, Object> archiveChatSession(
            @PathVariable String sessionId, HttpServletRequest request,
            @RequestParam(defaultValue = "true") boolean archived
    ) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("archived", chatHistoryService.setSessionArchived(requireUser(request), sessionId, archived));
        return response;
    }

    private String optionalUser(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthenticationInterceptor.AUTHENTICATED_USER_ID);
        return userId == null ? "" : String.valueOf(userId);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.HEADER);
        return value == null ? "" : String.valueOf(value);
    }

    private String requireUser(HttpServletRequest request) {
        String userId = optionalUser(request);
        if (!StringUtils.hasText(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }
}
