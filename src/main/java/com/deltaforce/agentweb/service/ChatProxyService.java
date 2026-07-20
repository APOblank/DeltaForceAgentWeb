package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

    private final AppProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ChatProxyService(AppProperties properties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(String message, Boolean debug, Boolean reactEvents) {
        Map<String, Object> payload = buildPayload(message, false, debug, reactEvents);
        HttpHeaders headers = buildHeaders();

        String baseUrl = trimTrailingSlash(properties.getFastapi().getBaseUrl());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = restTemplate.postForObject(
                    baseUrl + "/v1/chat/completions",
                    new HttpEntity<Map<String, Object>>(payload, headers),
                    Map.class
            );
            ChatResponse response = new ChatResponse(extractAnswer(raw), raw);
            response.setReactEvents(raw == null ? null : raw.get("react_events"));
            return response;
        } catch (RestClientException ex) {
            return new ChatResponse("FastAPI 智能体服务暂不可用，请确认 DeltaForceAgentHelper 已启动并监听 " + baseUrl + "。", null);
        }
    }

    public interface StreamCompleteHandler {
        String onComplete(String answer, List<Map<String, Object>> reactEvents);
    }

    public void streamChat(String message, Boolean debug, Boolean reactEvents, SseEmitter emitter, StreamCompleteHandler onComplete) {
        Map<String, Object> payload = buildPayload(message, true, debug, reactEvents == null ? Boolean.TRUE : reactEvents);
        HttpHeaders headers = buildHeaders();
        String baseUrl = trimTrailingSlash(properties.getFastapi().getBaseUrl());
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> collectedReactEvents = new ArrayList<Map<String, Object>>();

        try {
            restTemplate.execute(baseUrl + "/v1/chat/completions", HttpMethod.POST, request -> {
                request.getHeaders().putAll(headers);
                objectMapper.writeValue(request.getBody(), payload);
            }, response -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String raw = line.substring("data:".length()).trim();
                    if (raw.isEmpty()) {
                        continue;
                    }
                    emitter.send(SseEmitter.event().name("chunk").data(raw));
                    if ("[DONE]".equals(raw)) {
                        break;
                    }
                    answer.append(extractDeltaContent(raw));
                    Map<String, Object> reactEvent = extractReactEvent(raw);
                    if (reactEvent != null) {
                        collectedReactEvents.add(reactEvent);
                    }
                }
                if (onComplete != null) {
                    String assistantMessageId = onComplete.onComplete(answer.toString(), collectedReactEvents);
                    if (StringUtils.hasText(assistantMessageId)) {
                        Map<String, Object> saved = new HashMap<String, Object>();
                        saved.put("assistantMessageId", assistantMessageId);
                        emitter.send(SseEmitter.event().name("assistant_saved").data(objectMapper.writeValueAsString(saved)));
                    }
                }
                emitter.complete();
                return null;
            });
        } catch (Exception ex) {
            try {
                Map<String, Object> error = new HashMap<String, Object>();
                error.put("error", "FastAPI 智能体服务暂不可用，请确认 DeltaForceAgentHelper 已启动并监听 " + baseUrl + "。");
                emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error)));
            } catch (Exception ignored) {
            }
            emitter.completeWithError(ex);
        }
    }

    private Map<String, Object> buildPayload(String message, boolean stream, Boolean debug, Boolean reactEvents) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("model", properties.getFastapi().getModel());
        payload.put("stream", stream);
        if (reactEvents != null) {
            payload.put("react_events", reactEvents);
        }
        if (debug != null) {
            payload.put("debug", debug);
        }

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> userMessage = new HashMap<String, Object>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        messages.add(userMessage);
        payload.put("messages", messages);
        return payload;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(properties.getFastapi().getApiKey())) {
            headers.setBearerAuth(properties.getFastapi().getApiKey());
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
