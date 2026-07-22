package com.deltaforce.agentweb;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendContractTest {

    @Test
    void desktopCollapseAndDrawerBreakpointsDoNotOverlap() throws IOException {
        String css = resource("static/styles.css");

        assertTrue(css.contains("@media (min-width: 1281px)"));
        assertTrue(css.contains("@media (max-width: 1280px)"));
        assertFalse(css.contains("@media (min-width: 1081px)"));
    }

    @Test
    void mainSiteAndStandaloneAdminShareSessionAuthentication() throws IOException {
        String app = resource("static/app.js");
        String admin = resource("static/admin.js");

        assertTrue(app.contains("sessionStorage.setItem(\"delta-force-user\""));
        assertTrue(admin.contains("sessionStorage.getItem(key)"));
        assertFalse(admin.contains("localStorage.getItem(\"delta-force-user\")"));
    }

    @Test
    void rippleMotionIncludesButtonStyledLinks() throws IOException {
        String app = resource("static/app.js");
        String css = resource("static/styles.css");

        assertTrue(app.contains("a.primary-button, a.ghost-button, a.chip-button, a.tool-button"));
        assertTrue(css.contains("a.primary-button.is-rippling::after"));
    }

    @Test
    void onlyHomeNewsAndLoginArePublicRoutes() throws IOException {
        String app = resource("static/app.js");

        assertTrue(app.contains("new Set([\"/\", \"/news\", \"/login\"])") );
        assertFalse(app.contains("new Set([\"/\", \"/market\""));
    }

    @Test
    void registrationControlsExistInLoginAndAdminInterfaces() throws IOException {
        String app = resource("static/app.js");
        String admin = resource("static/admin.html");

        assertTrue(app.contains("/api/auth/registration"));
        assertTrue(app.contains("id=\"registration-tab\""));
        assertTrue(admin.contains("id=\"registration-toggle\""));
    }

    @Test
    void mobileChatPanelsUseAccessibleProgressiveDisclosure() throws IOException {
        String app = resource("static/app.js");
        String css = resource("static/styles.css");

        assertTrue(app.contains("data-mobile-chat-panel"));
        assertTrue(app.contains("aria-controls=\"history-panel-content\""));
        assertTrue(app.contains("aria-controls=\"react-panel-content\""));
        assertTrue(app.contains("trigger.setAttribute(\"aria-expanded\", String(expanded))"));
        assertTrue(css.contains("@media (max-width: 720px) and (orientation: portrait)"));
        assertTrue(css.contains(".mobile-chat-panel:not(.is-expanded) .mobile-chat-panel-content"));
        assertTrue(css.contains("min-height: 48px"));
    }

    private String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
