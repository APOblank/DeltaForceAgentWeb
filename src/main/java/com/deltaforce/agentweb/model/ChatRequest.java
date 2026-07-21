package com.deltaforce.agentweb.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChatRequest {

    @NotBlank(message = "message cannot be blank")
    @Size(max = 12000, message = "单条消息不能超过 12000 个字符")
    private String message;
    private Boolean debug;
    private Boolean reactEvents;
    @Size(max = 64, message = "sessionId 过长")
    @Pattern(regexp = "^[A-Za-z0-9._:-]*$", message = "sessionId 格式不合法")
    private String sessionId;
    @Size(max = 64, message = "messageId 过长")
    @Pattern(regexp = "^[A-Za-z0-9._:-]*$", message = "messageId 格式不合法")
    private String messageId;
    @Pattern(regexp = "^(auto|plan|execute)?$", message = "conversationMode 不受支持")
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
