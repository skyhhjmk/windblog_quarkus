package com.biliwind.blog.controller.api;

import com.biliwind.blog.service.security.SecurityRateLimitService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPostContentPasswordRateLimitTest {

    private static UserPostContentController controller() {
        UserPostContentController controller = new UserPostContentController();
        controller.securityRateLimitService = new SecurityRateLimitService();
        return controller;
    }

    @Test
    void shouldBlockRepeatedGuessesFromOneVisitorOnOnePost() {
        UserPostContentController controller = controller();
        for (int attempt = 0; attempt < UserPostContentController.PASSWORD_ATTEMPTS_PER_POST_AND_IP; attempt++) {
            assertTrue(controller.tryAcquirePasswordAttempt(1L, "203.0.113.5"));
        }
        assertFalse(controller.tryAcquirePasswordAttempt(1L, "203.0.113.5"));
        assertTrue(controller.tryAcquirePasswordAttempt(1L, "203.0.113.6"));
    }

    @Test
    void shouldResetPerPostCounterAfterSuccessfulUnlock() {
        UserPostContentController controller = controller();
        for (int attempt = 0; attempt < UserPostContentController.PASSWORD_ATTEMPTS_PER_POST_AND_IP; attempt++) {
            controller.tryAcquirePasswordAttempt(2L, "203.0.113.5");
        }
        controller.securityRateLimitService.clear(UserPostContentController.passwordAttemptKey(2L, "203.0.113.5"));

        assertTrue(controller.tryAcquirePasswordAttempt(2L, "203.0.113.5"));
    }

    @Test
    void shouldCapOneVisitorWalkingAcrossManyPosts() {
        UserPostContentController controller = controller();
        int allowed = 0;
        for (long postId = 100; postId < 100 + UserPostContentController.PASSWORD_ATTEMPTS_PER_IP + 5; postId++) {
            if (controller.tryAcquirePasswordAttempt(postId, "198.51.100.9")) {
                allowed++;
            }
        }
        assertTrue(allowed == UserPostContentController.PASSWORD_ATTEMPTS_PER_IP);
    }

    @Test
    void shouldCapDistributedGuessingOnOnePost() {
        UserPostContentController controller = controller();
        for (int visitor = 0; visitor < UserPostContentController.PASSWORD_ATTEMPTS_PER_POST; visitor++) {
            assertTrue(controller.tryAcquirePasswordAttempt(3L, "10.1." + (visitor / 250) + "." + (visitor % 250)));
        }
        assertFalse(controller.tryAcquirePasswordAttempt(3L, "10.9.9.9"));
    }
}
