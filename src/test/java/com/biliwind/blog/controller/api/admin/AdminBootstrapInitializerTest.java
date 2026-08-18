package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminBootstrapInitializerTest {

    @Test
    void shouldAllowBootstrapWhenNoUserUsesConfiguredIdentity() {
        assertDoesNotThrow(() -> AdminBootstrapInitializer.ensureNoExistingUser(null));
    }

    @Test
    void shouldRejectBootstrapWhenConfiguredIdentityIsAlreadyUsed() {
        User existingUser = new User();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AdminBootstrapInitializer.ensureNoExistingUser(existingUser));

        assertTrue(exception.getMessage().contains("用户名或邮箱已被现有用户占用"));
    }
}
