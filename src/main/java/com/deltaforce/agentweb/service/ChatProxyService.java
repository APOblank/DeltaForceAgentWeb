package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatProxyService {

    private static final int MAX_SYSTEM_MESSAGE_LENGTH = 12_000;
    private static final int MAX_ATTEMPTS = 2;

    private final AppProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Neo4jDataCenterService statsService;

    public ChatProxyService(AppProperties properties, @Qualifier("chatRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper,
                            Neo4jDataCenterService statsService) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.statsService = statsService;
    }

    public ChatResponse chat(String message, Boolean debug, Boolean reactEvents, String userId, String sessionId,
                             String messageId, String conversationMode, String requestId) {
        Map<String, Object> payload = buildPayload(message, false, debug, reactEvents, userId, sessionId, messageId, conversationMode);
        HttpHeaders headers = buildHeaders(userId, requestId);

        String baseUrl = trimTrailingSlash(properties.getFastapi().getBaseUrl());
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = restTemplate.postForObject(
                        baseUrl + "/v1/chat/completions",
                        new HttpEntity<Map<String, Object>>(payload, headers),
                        Map.class
                );
                ChatResponse response = new ChatResponse(extractAnswer(raw), raw);
                response.setReactEvents(raw == null ? null : raw.get("react_events"));
                response.setConversation(raw == null ? null : raw.get("conversation"));
                if (raw != null && raw.get("session_id") != null) {
                    response.setSessionId(String.valueOf(raw.get("session_id")));
                }
                return response;
            } catch (RestClientException ex) {
                AgentApiException failure = toAgentException(ex, requestId);
                if (!shouldRetry(failure, attempt)) {
                    throw failure;
                }
                pauseBeforeRetry(failure);
            }
        }
        throw new AgentApiException(502, "agent_unavailable", "智能体服务暂不可用", requestId, null);
    }

    public interface StreamCompleteHandler {
        String onComplete(String answer, List<Map<String, Object>> reactEvents);
    }

    public void streamChat(String message, Boolean debug, Boolean reactEvents, String userId, String sessionId,
                           String messageId, String conversationMode, String requestId, SseEmitter emitter,
                           StreamCompleteHandler onComplete) {
        Map<String, Object> payload = buildPayload(message, true, debug, reactEvents == null ? Boolean.TRUE : reactEvents, userId, sessionId, messageId, conversationMode);
        HttpHeaders headers = buildHeaders(userId, requestId);
        String baseUrl = trimTrailingSlash(properties.getFastapi().getBaseUrl());
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> collectedReactEvents = new ArrayList<Map<String, Object>>();
        boolean[] streamStarted = {false};
        boolean[] streamFailed = {false};
        boolean[] sawDone = {false};

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restTemplate.execute(baseUrl + "/v1/chat/completions", HttpMethod.POST, request -> {
                    request.getHeaders().putAll(headers);
                    objectMapper.writeValue(request.getBody(), payload);
                }, response -> {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(":")) {
                            emitter.send(SseEmitter.event().comment(line.substring(1).trim()));
                            continue;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String raw = line.substring("data:".length()).trim();
                        if (raw.isEmpty()) {
                            continue;
                        }
                        streamStarted[0] = true;
                        if ("[DONE]".equals(raw)) {
                            sawDone[0] = true;
                            break;
                        }
                        Map<String, Object> reactEvent = extractReactEvent(raw);
                        SseEmitter.SseEventBuilder outgoing = SseEmitter.event().name("chunk").data(raw);
                        if (reactEvent != null && reactEvent.get("event_id") != null) {
                            outgoing.id(String.valueOf(reactEvent.get("event_id")));
                        }
                        emitter.send(outgoing);
                        if (extractDeltaError(raw) != null) {
                            streamFailed[0] = true;
                        }
                        answer.append(extractDeltaContent(raw));
                        if (reactEvent != null) {
                            upsertReactEvent(collectedReactEvents, reactEvent);
                        }
                    }
                    return null;
                });
                break;
            } catch (RestClientException ex) {
                AgentApiException failure = toAgentException(ex, requestId);
                if (!streamStarted[0] && shouldRetry(failure, attempt)) {
                    pauseBeforeRetry(failure);
                    continue;
                }
                sendStreamError(emitter, failure);
                emitter.complete();
                return;
            } catch (Exception ex) {
                sendStreamError(emitter, new AgentApiException(502, "agent_unavailable", "智能体服务暂不可用", requestId, null));
                emitter.complete();
                return;
            }
        }

        try {
            if (!sawDone[0]) {
                sendStreamError(emitter, new AgentApiException(502, "incomplete_stream", "智能体流式响应意外中断", requestId, null));
            } else if (!streamFailed[0] && onComplete != null) {
                String assistantMessageId = onComplete.onComplete(answer.toString(), collectedReactEvents);
                if (StringUtils.hasText(assistantMessageId)) {
                    Map<String, Object> saved = new HashMap<String, Object>();
                    saved.put("assistantMessageId", assistantMessageId);
                    emitter.send(SseEmitter.event().name("assistant_saved").data(objectMapper.writeValueAsString(saved)));
                }
            }
            if (sawDone[0]) {
                emitter.send(SseEmitter.event().name("chunk").data("[DONE]"));
            }
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private Map<String, Object> buildPayload(String message, boolean stream, Boolean debug, Boolean reactEvents, String userId, String sessionId, String messageId, String conversationMode) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("model", properties.getFastapi().getModel());
        payload.put("stream", stream);
        if (reactEvents != null) {
            payload.put("react_events", reactEvents);
        }
        if (debug != null) {
            payload.put("debug", debug);
        }
        if (StringUtils.hasText(sessionId)) {
            payload.put("session_id", sessionId);
        }
        if (StringUtils.hasText(messageId)) {
            payload.put("message_id", messageId);
        }
        payload.put("conversation_mode", StringUtils.hasText(conversationMode) ? conversationMode : "auto");

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        if (properties.getFastapi().isPreloadPersonalStats() && StringUtils.hasText(userId)) {
            try {
                Map<String, Object> stats = statsService.buildAiContext(userId, message);
                Object summaryObject = stats.get("summary");
                if (summaryObject instanceof Map && ((Map<?, ?>) summaryObject).get("totalBattles") instanceof Number
                        && ((Number) ((Map<?, ?>) summaryObject).get("totalBattles")).longValue() > 0) {
                    Map<String, Object> systemMessage = new HashMap<String, Object>();
                    systemMessage.put("role", "system");
                    String systemContent = "以下是服务端经过当前登录用户权限校验后提供的个人战绩数据。" +
                            "只能使用这些数据回答该用户的个人战绩问题，不得推断、请求或引用其他用户数据。\n" +
                            objectMapper.writeValueAsString(stats);
                    if (systemContent.length() > MAX_SYSTEM_MESSAGE_LENGTH) {
                        systemContent = systemContent.substring(0, MAX_SYSTEM_MESSAGE_LENGTH - 12) + "\n[数据已截断]";
                    }
                    systemMessage.put("content", systemContent);
                    messages.add(systemMessage);
                }
            } catch (Exception ignored) {
                // Personal stats are optional context; chat remains available if the
                // dedicated database is temporarily offline.
            }
        }
        Map<String, Object> userMessage = new HashMap<String, Object>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        messages.add(userMessage);
        payload.put("messages", messages);
        return payload;
    }

    private HttpHeaders buildHeaders(String userId, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        if (StringUtils.hasText(properties.getFastapi().getApiKey())) {
            headers.setBearerAuth(properties.getFastapi().getApiKey());
        }
        if (StringUtils.hasText(userId)) {
            headers.set("X-User-ID", userId);
        }
        if (StringUtils.hasText(requestId)) {
            headers.set("X-Request-ID", requestId);
        }
        return headers;
    }

    private String extractAnswer(Map<String, Object> raw) {
        if (raw == null) {
            return "";
        }
        Object choicesObject = raw.get("choices");
        if (!(choicesObject instanceof List)) {
            return "";
        }
        List<?> choices = (List<?>) choicesObject;
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            return "";
        }
        Object messageObject = ((Map<?, ?>) choices.get(0)).get("message");
        if (!(messageObject instanceof Map)) {
            return "";
        }
        Object content = ((Map<?, ?>) messageObject).get("content");
        return content == null ? "" : String.valueOf(content);
    }

    private String extractDeltaContent(String raw) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(raw, Map.class);
            Object choicesObject = chunk.get("choices");
            if (!(choicesObject instanceof List)) {
                return "";
            }
            List<?> choices = (List<?>) choicesObject;
            if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
                return "";
            }
            Object deltaObject = ((Map<?, ?>) choices.get(0)).get("delta");
            if (!(deltaObject instanceof Map)) {
                return "";
            }
            Object content = ((Map<?, ?>) deltaObject).get("content");
            return content == null ? "" : String.valueOf(content);
        } catch (Exception ex) {
            return "";
        }
    }

    private Map<String, Object> extractReactEvent(String raw) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(raw, Map.class);
            Object choicesObject = chunk.get("choices");
            if (!(choicesObject instanceof List)) {
                return null;
            }
            List<?> choices = (List<?>) choicesObject;
            if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
                return null;
            }
            Object deltaObject = ((Map<?, ?>) choices.get(0)).get("delta");
            if (!(deltaObject instanceof Map)) {
                return null;
            }
            Object event = ((Map<?, ?>) deltaObject).get("react_event");
            if (!(event instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedEvent = (Map<String, Object>) event;
            return typedEvent;
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> extractDeltaError(String raw) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(raw, Map.class);
            Object choicesObject = chunk.get("choices");
            if (!(choicesObject instanceof List) || ((List<?>) choicesObject).isEmpty()
                    || !(((List<?>) choicesObject).get(0) instanceof Map)) {
                return null;
            }
            Object deltaObject = ((Map<?, ?>) ((List<?>) choicesObject).get(0)).get("delta");
            if (!(deltaObject instanceof Map)) {
                return null;
            }
            Object error = ((Map<?, ?>) deltaObject).get("error");
            if (!(error instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedError = (Map<String, Object>) error;
            return typedError;
        } catch (Exception ex) {
            return null;
        }
    }

    private void upsertReactEvent(List<Map<String, Object>> events, Map<String, Object> event) {
        String eventId = event.get("event_id") == null ? "" : String.valueOf(event.get("event_id"));
        if (StringUtils.hasText(eventId)) {
            for (int index = 0; index < events.size(); index++) {
                if (eventId.equals(String.valueOf(events.get(index).get("event_id")))) {
                    events.set(index, event);
                    sortReactEvents(events);
                    return;
                }
            }
        }
        events.add(event);
        sortReactEvents(events);
    }

    private void sortReactEvents(List<Map<String, Object>> events) {
        events.sort((left, right) -> Long.compare(eventSequence(left), eventSequence(right)));
    }

    private long eventSequence(Map<String, Object> event) {
        Object value = event.get("sequence");
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }

    private AgentApiException toAgentException(RestClientException ex, String fallbackRequestId) {
        if (ex instanceof HttpStatusCodeException statusError) {
            int status = statusError.getStatusCode().value();
            String code = "agent_http_" + status;
            String message = "智能体服务请求失败";
            String requestId = fallbackRequestId;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(statusError.getResponseBodyAsByteArray(), Map.class);
                Object errorObject = body.get("error");
                if (errorObject instanceof Map) {
                    Object value = ((Map<?, ?>) errorObject).get("code");
                    if (value != null) code = String.valueOf(value);
                    value = ((Map<?, ?>) errorObject).get("message");
                    if (value != null) message = String.valueOf(value);
                }
                if (body.get("request_id") != null) {
                    requestId = String.valueOf(body.get("request_id"));
                }
            } catch (Exception ignored) {
                if (StringUtils.hasText(statusError.getStatusText())) {
                    message = statusError.getStatusText();
                }
            }
            Integer retryAfter = parseRetryAfter(statusError.getResponseHeaders());
            return new AgentApiException(status, code, message, requestId, retryAfter);
        }
        if (ex instanceof ResourceAccessException) {
            return new AgentApiException(504, "request_timeout", "智能体服务响应超时", fallbackRequestId, null);
        }
        return new AgentApiException(502, "agent_unavailable", "智能体服务暂不可用", fallbackRequestId, null);
    }

    private Integer parseRetryAfter(HttpHeaders headers) {
        if (headers == null || !StringUtils.hasText(headers.getFirst("Retry-After"))) {
            return null;
        }
        try {
            return Math.max(0, Integer.parseInt(headers.getFirst("Retry-After").trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean shouldRetry(AgentApiException failure, int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return false;
        }
        if (failure.getStatus() == 409 || failure.getStatus() == 502 || failure.getStatus() == 504) {
            return true;
        }
        return failure.getStatus() == 429
                && failure.getRetryAfterSeconds() != null
                && failure.getRetryAfterSeconds() <= 2;
    }

    private void pauseBeforeRetry(AgentApiException failure) {
        long delayMillis = failure.getRetryAfterSeconds() == null
                ? 300L : Math.min(2_000L, failure.getRetryAfterSeconds() * 1_000L);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentApiException(503, "request_interrupted", "智能体请求已中断",
                    failure.getRequestId(), null);
        }
    }

    private void sendStreamError(SseEmitter emitter, AgentApiException failure) {
        try {
            Map<String, Object> error = new HashMap<String, Object>();
            error.put("message", failure.getMessage());
            error.put("code", failure.getCode());

            Map<String, Object> delta = new HashMap<String, Object>();
            delta.put("error", error);
            Map<String, Object> choice = new HashMap<String, Object>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", "error");
            Map<String, Object> chunk = new HashMap<String, Object>();
            chunk.put("choices", List.of(choice));
            if (StringUtils.hasText(failure.getRequestId())) {
                chunk.put("request_id", failure.getRequestId());
            }
            if (failure.getRetryAfterSeconds() != null) {
                chunk.put("retry_after", failure.getRetryAfterSeconds());
            }
            emitter.send(SseEmitter.event().name("chunk").data(objectMapper.writeValueAsString(chunk)));
            emitter.send(SseEmitter.event().name("chunk").data("[DONE]"));
        } catch (Exception ignored) {
            // The browser may already have disconnected.
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "http://127.0.0.1:8000";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
