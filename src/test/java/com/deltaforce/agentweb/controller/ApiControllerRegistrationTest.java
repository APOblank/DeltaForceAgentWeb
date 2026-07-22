package com.deltaforce.agentweb.controller;

import com.deltaforce.agentweb.model.AuthRequest;
import com.deltaforce.agentweb.model.AuthResponse;
import com.deltaforce.agentweb.service.AdminService;
import com.deltaforce.agentweb.service.ChatHistoryService;
import com.deltaforce.agentweb.service.ChatProxyService;
import com.deltaforce.agentweb.service.GunModifierService;
import com.deltaforce.agentweb.service.GunsmithService;
import com.deltaforce.agentweb.service.KnowledgeService;
import com.deltaforce.agentweb.service.MarketDataService;
import com.deltaforce.agentweb.service.ReadinessService;
import com.deltaforce.agentweb.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiControllerRegistrationTest {

    @Mock private MarketDataService marketDataService;
    @Mock private ReadinessService readinessService;
    @Mock private AdminService adminService;
    @Mock private GunsmithService gunsmithService;
    @Mock private GunModifierService gunModifierService;
    @Mock private KnowledgeService knowledgeService;
    @Mock private ChatProxyService chatProxyService;
    @Mock private UserService userService;
    @Mock private ChatHistoryService chatHistoryService;
    @Mock private ExecutorService chatStreamExecutor;

    private ApiController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiController(
                marketDataService,
                readinessService,
                adminService,
                gunsmithService,
                gunModifierService,
                knowledgeService,
                chatProxyService,
                userService,
                chatHistoryService,
                new ObjectMapper(),
                chatStreamExecutor
        );
    }

    @Test
    void rejectsRegistrationWhileAdminSwitchIsClosed() {
        when(adminService.registrationEnabled()).thenReturn(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.register(request())
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        assertEquals("当前未开放新用户注册", error.getReason());
        verifyNoInteractions(userService);
    }

    @Test
    void delegatesRegistrationWhileAdminSwitchIsOpen() {
        AuthRequest request = request();
        AuthResponse expected = new AuthResponse("user-1", "new-player", "new-player", "USER");
        when(adminService.registrationEnabled()).thenReturn(true);
        when(userService.register("new-player", "safe-password")).thenReturn(expected);

        assertSame(expected, controller.register(request));
    }

    private AuthRequest request() {
        AuthRequest request = new AuthRequest();
        request.setUsername("new-player");
        request.setPassword("safe-password");
        return request;
    }
}
