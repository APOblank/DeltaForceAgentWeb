package com.deltaforce.agentweb.service;

import com.deltaforce.agentweb.config.AppProperties;
import com.deltaforce.agentweb.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatProxyServiceTest {

    private MockRestServiceServer server;
    private ChatProxyService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getFastapi().setBaseUrl("http://agent-api:8000");
        properties.getFastapi().setApiKey("service-secret");
        properties.getFastapi().setModel("DF_agent");
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        Neo4jDataCenterService statsService = mock(Neo4jDataCenterService.class);
        when(statsService.buildAiContext(anyString(), anyString())).thenReturn(Map.of());
        service = new ChatProxyService(properties, restTemplate, new ObjectMapper(), statsService);
    }

    @Test
    void sendsV2IdentityAndRequestMetadataInHeaders() {
        server.expect(requestTo("http://agent-api:8000/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer service-secret"))
                .andExpect(header("X-User-ID", "user-1"))
                .andExpect(header("X-Request-ID", "java-request-1"))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.model").value("DF_agent"))
                .andExpect(jsonPath("$.session_id").value("session-1"))
                .andExpect(jsonPath("$.message_id").value("message-1"))
                .andExpect(jsonPath("$.conversation_mode").value("new"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}],"session_id":"session-1"}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = service.chat("hello", false, true, "user-1", "session-1",
                "message-1", "new", "java-request-1");

        assertEquals("ok", response.getAnswer());
        assertEquals("session-1", response.getSessionId());
        server.verify();
    }

    @Test
    void retriesSessionBusyWithTheSameMessageIdAndPreservesError() {
        server.expect(ExpectedCount.twice(), requestTo("http://agent-api:8000/v1/chat/completions"))
                .andExpect(jsonPath("$.message_id").value("message-1"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .header("Retry-After", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"message":"another request is running","code":"session_busy"},"request_id":"agent-request-1"}
                                """));

        AgentApiException error = assertThrows(AgentApiException.class, () ->
                service.chat("hello", false, false, "user-1", "session-1",
                        "message-1", "auto", "java-request-1"));

        assertEquals(409, error.getStatus());
        assertEquals("session_busy", error.getCode());
        assertEquals("agent-request-1", error.getRequestId());
        server.verify();
    }
}
