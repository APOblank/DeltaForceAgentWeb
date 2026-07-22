package com.deltaforce.agentweb.controller;

import com.deltaforce.agentweb.config.CorrelationIdFilter;
import com.deltaforce.agentweb.service.AgentApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        FieldError field = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = field == null ? "请求参数不合法" : field.getField() + "：" + field.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraint(ConstraintViolationException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", safeMessage(ex.getMessage(), "请求无法处理"), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, status.name(), safeMessage(ex.getReason(), status.getReasonPhrase()), request);
    }

    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<Map<String, Object>> agentApi(AgentApiException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        if (status == null) status = HttpStatus.BAD_GATEWAY;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("code", ex.getCode());
        body.put("message", safeMessage(ex.getMessage(), "智能体服务请求失败"));
        body.put("path", request.getRequestURI());
        body.put("requestId", StringUtils.hasText(ex.getRequestId()) ? ex.getRequestId() : requestId(request));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex.getRetryAfterSeconds() != null) {
            response.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("未处理的请求异常 requestId={} path={}", requestId, request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试", request);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("requestId", requestId(request));
        return ResponseEntity.status(status).body(body);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.HEADER);
        return value == null ? "" : String.valueOf(value);
    }

    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
