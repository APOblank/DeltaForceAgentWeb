package com.deltaforce.agentweb.model;

public class ChatResponse {

    private String answer;
    private Object raw;
    private Object reactEvents;
    private Object conversation;
    private String sessionId;
    private String messageId;

    public ChatResponse() {
    }

    public ChatResponse(String answer, Object raw) {
        this.answer = answer;
        this.raw = raw;
    }

    public ChatResponse(String answer, Object raw, String sessionId) {
        this.answer = answer;
        this.raw = raw;
        this.sessionId = sessionId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Object getRaw() {
        return raw;
    }

    public void setRaw(Object raw) {
        this.raw = raw;
    }

    public Object getReactEvents() {
        return reactEvents;
    }

    public void setReactEvents(Object reactEvents) {
        this.reactEvents = reactEvents;
    }

    public Object getConversation() {
        return conversation;
    }

    public void setConversation(Object conversation) {
        this.conversation = conversation;
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
}
