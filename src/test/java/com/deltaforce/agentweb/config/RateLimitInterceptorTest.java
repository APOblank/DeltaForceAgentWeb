package com.deltaforce.agentweb.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitInterceptorTest {

    @Test
    void appliesStricterAuthenticationLimitPerAddress() {
        AppProperties properties = new AppProperties();
        properties.getRateLimits().setAuthPerMinute(2);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.preHandle(request, response, new Object());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(429, error.getStatusCode().value());
        assertNotNull(response.getHeader("Retry-After"));
    }
}
