package com.deltaforce.agentweb.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthRequest {

    @NotBlank(message = "username cannot be blank")
    @Size(min = 3, max = 64, message = "用户名长度必须为 3 到 64 个字符")
    @Pattern(regexp = "^[\\p{L}\\p{N}._-]+$", message = "用户名只能包含文字、数字、点、下划线和短横线")
    private String username;

    @NotBlank(message = "password cannot be blank")
    @Size(max = 128, message = "密码不能超过 128 个字符")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
