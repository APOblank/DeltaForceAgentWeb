package com.deltaforce.agentweb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final long API_LIMIT = 5L * 1024 * 1024;
    private static final long LEGACY_UPLOAD_LIMIT = 105L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        long limit = request.getRequestURI().equals("/api/data-center/import/legacy-backup")
                ? LEGACY_UPLOAD_LIMIT : API_LIMIT;
        if (request.getRequestURI().startsWith("/api/") && contentLength > limit) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "请求内容过大");
            return;
        }
        chain.doFilter(request, response);
    }
}
