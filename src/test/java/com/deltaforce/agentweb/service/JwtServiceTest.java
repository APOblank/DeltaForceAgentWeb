package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.AuthResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    @Test
    void issuesAndValidatesRoleBoundToken() {
        JwtService service = service("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN");
        AuthResponse user = new AuthResponse("user-1", "player", "Player", UserService.ROLE_ADMIN);

        String token = service.issue(user);
        JwtService.Principal principal = service.authenticate("Bearer " + token);

        assertEquals("user-1", principal.userId());
        assertEquals(UserService.ROLE_ADMIN, principal.role());
    }

    @Test
    void rejectsTamperedToken() {
        JwtService service = service("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN");
        AuthResponse user = new AuthResponse("user-1", "player", "Player", UserService.ROLE_USER);
        String token = service.issue(user);

        assertThrows(ResponseStatusException.class,
                () -> service.authenticate("Bearer " + token.substring(0, token.length() - 1) + "x"));
    }

    @Test
    void rejectsPlaceholderSecretAtStartup() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setJwtSecret("CHANGE_ME_TO_A_LONG_RANDOM_JWT_SECRET_48_CHARACTERS");
        JwtService service = new JwtService(properties, new ObjectMapper());

        assertThrows(IllegalStateException.class, service::validateConfiguration);
    }

    private JwtService service(String secret) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setJwtSecret(secret);
        JwtService service = new JwtService(properties, new ObjectMapper());
        service.validateConfiguration();
        return service;
    }
}
