package com.deltaforce.agentweb.controller;

import com.deltaforce.agentweb.config.JwtAuthenticationInterceptor;
import com.deltaforce.agentweb.service.Neo4jDataCenterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataCenterControllerTest {
    private Neo4jDataCenterService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(Neo4jDataCenterService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DataCenterController(service)).build();
    }

    @Test
    void rejectsAnonymousDataCenterRequests() throws Exception {
        mockMvc.perform(get("/api/data-center/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passesOnlyAuthenticatedRequestUserToService() throws Exception {
        when(service.summary("authenticated-user", null)).thenReturn(Collections.emptyMap());

        mockMvc.perform(get("/api/data-center/summary")
                        .requestAttr(JwtAuthenticationInterceptor.AUTHENTICATED_USER_ID, "authenticated-user"))
                .andExpect(status().isOk());

        verify(service).summary("authenticated-user", null);
    }
}
