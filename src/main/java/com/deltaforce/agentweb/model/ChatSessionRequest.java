package com.deltaforce.agentweb.model;

import javax.validation.constraints.NotBlank;

public class ChatSessionRequest {

    @NotBlank(message = "userId cannot be blank")
    private String userId;

    private String title;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
