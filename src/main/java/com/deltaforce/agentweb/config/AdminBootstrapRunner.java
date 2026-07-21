package com.deltaforce.agentweb.config;

import com.deltaforce.agentweb.service.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AppProperties properties;
    private final UserService userService;

    public AdminBootstrapRunner(AppProperties properties, UserService userService) {
        this.properties = properties;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getSecurity().getBootstrapAdminUsername();
        String password = properties.getSecurity().getBootstrapAdminPassword();
        boolean hasUsername = StringUtils.hasText(username);
        boolean hasPassword = StringUtils.hasText(password);
        if (hasUsername != hasPassword) {
            throw new IllegalStateException("管理员初始化账号和密码必须同时配置");
        }
        if (hasUsername) {
            if (password.length() < 16) {
                throw new IllegalStateException("管理员初始化密码至少需要 16 位");
            }
            userService.ensureBootstrapAdmin(username, password);
        }
    }
}
