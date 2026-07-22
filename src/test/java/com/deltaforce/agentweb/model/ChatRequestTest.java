package com.deltaforce.agentweb.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsV2ConversationModesAndIds() {
        ChatRequest request = request("new");
        request.setSessionId("session:@-1");
        request.setMessageId("message:@-1");

        assertTrue(validator.validate(request).isEmpty());
        assertTrue(validator.validate(request("continue")).isEmpty());
        assertTrue(validator.validate(request("auto")).isEmpty());
    }

    @Test
    void rejectsRemovedConversationModes() {
        assertFalse(validator.validate(request("plan")).isEmpty());
        assertFalse(validator.validate(request("execute")).isEmpty());
    }

    private ChatRequest request(String mode) {
        ChatRequest request = new ChatRequest();
        request.setMessage("hello");
        request.setConversationMode(mode);
        return request;
    }
}
