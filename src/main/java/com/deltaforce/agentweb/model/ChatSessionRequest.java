package com.deltaforce.agentweb.model;

import jakarta.validation.constraints.Size;

public class ChatSessionRequest {
    @Size(max = 80, message = "会话标题不能超过 80 个字符")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
