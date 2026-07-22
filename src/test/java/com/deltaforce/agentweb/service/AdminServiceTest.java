package com.deltaforce.agentweb.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServiceTest {

    @Test
    void acceptsRoleBasedAdministrator() {
        assertTrue(AdminService.isAdminIdentity("ADMIN", "site-admin"));
    }

    @Test
    void acceptsLegacyReservedAdministratorIdentity() {
        assertTrue(AdminService.isAdminIdentity("USER", "admin"));
    }

    @Test
    void rejectsRegularUsers() {
        assertFalse(AdminService.isAdminIdentity("USER", "player"));
        assertFalse(AdminService.isAdminIdentity("", ""));
    }

    @Test
    void registrationIsClosedByDefault() {
        assertFalse(AdminService.isRegistrationEnabled(Map.of()));
        assertFalse(AdminService.isRegistrationEnabled(Map.of("registrationEnabled", false)));
    }

    @Test
    void registrationAcceptsPersistedBooleanOrLegacyString() {
        assertTrue(AdminService.isRegistrationEnabled(Map.of("registrationEnabled", true)));
        assertTrue(AdminService.isRegistrationEnabled(Map.of("registrationEnabled", "TRUE")));
    }
}
