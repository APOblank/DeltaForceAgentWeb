package com.deltaforce.agentweb.config;

import com.deltaforce.agentweb.service.JwtService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {
    public static final String AUTHENTICATED_USER_ID = "authenticatedUserId";
    private final JwtService jwtService;

    public JwtAuthenticationInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization)) {
            request.setAttribute(AUTHENTICATED_USER_ID, jwtService.authenticatedUserId(authorization));
        }
        return true;
    }
}
