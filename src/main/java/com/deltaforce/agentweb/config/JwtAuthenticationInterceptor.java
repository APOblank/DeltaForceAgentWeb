package com.deltaforce.agentweb.config;

import com.deltaforce.agentweb.service.JwtService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {
    public static final String AUTHENTICATED_USER_ID = "authenticatedUserId";
    public static final String AUTHENTICATED_ROLE = "authenticatedRole";
    private final JwtService jwtService;

    public JwtAuthenticationInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization)) {
            JwtService.Principal principal = jwtService.authenticate(authorization);
            request.setAttribute(AUTHENTICATED_USER_ID, principal.userId());
            request.setAttribute(AUTHENTICATED_ROLE, principal.role());
        }
        return true;
    }
}
