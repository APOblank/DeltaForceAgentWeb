package com.deltaforce.agentweb.model;

import javax.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank(message = "message cannot be blank")
    private String message;
    private Boolean debug;
    private Boolean reactEvents;
    private String sessionId;
    private String messageId;
    private String conversationMode;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
    }

    public Boolean getReactEvents() {
        return reactEvents;
    }

    public void setReactEvents(Boolean reactEvents) {
        this.reactEvents = reactEvents;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationMode() {
        return conversationMode;
    }

    public void setConversationMode(String conversationMode) {
        this.conversationMode = conversationMode;
    }
}
