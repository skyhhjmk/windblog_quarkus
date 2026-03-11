package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostAccessService 单元测试
 * 测试文章访问服务
 */
@QuarkusTest
class PostAccessServiceTest {

    @Inject
    PostAccessService postAccessService;

    @Test
    void shouldReturnTrueForPrivatePost() {
        Post post = new Post();
        post.visibility = 1;

        assertTrue(postAccessService.isPrivate(post));
    }

    @Test
    void shouldReturnFalseForPublicPost() {
        Post post = new Post();
        post.visibility = 0;

        assertFalse(postAccessService.isPrivate(post));
    }

    @Test
    void shouldReturnFalseForPasswordProtectedPost() {
        Post post = new Post();
        post.visibility = 2;

        assertFalse(postAccessService.isPrivate(post));
    }

    @Test
    void shouldReturnTrueForPasswordProtectedPost() {
        Post post = new Post();
        post.visibility = 2;

        assertTrue(postAccessService.isPasswordProtected(post));
    }

    @Test
    void shouldReturnFalseForPublicPostWhenCheckingPasswordProtection() {
        Post post = new Post();
        post.visibility = 0;

        assertFalse(postAccessService.isPasswordProtected(post));
    }

    @Test
    void shouldReturnFalseForPrivatePostWhenCheckingPasswordProtection() {
        Post post = new Post();
        post.visibility = 1;

        assertFalse(postAccessService.isPasswordProtected(post));
    }

    @Test
    void shouldVerifyCorrectPassword() {
        Post post = new Post();
        post.password = "secret123";

        assertTrue(postAccessService.verifyPassword(post, "secret123"));
    }

    @Test
    void shouldNotVerifyIncorrectPassword() {
        Post post = new Post();
        post.password = "secret123";

        assertFalse(postAccessService.verifyPassword(post, "wrongpassword"));
    }

    @Test
    void shouldNotVerifyPasswordWhenSubmittedPasswordIsNull() {
        Post post = new Post();
        post.password = "secret123";

        assertFalse(postAccessService.verifyPassword(post, null));
    }

    @Test
    void shouldVerifyPasswordWhenBothAreNull() {
        Post post = new Post();
        post.password = null;

        assertTrue(postAccessService.verifyPassword(post, null));
    }

    @Test
    void shouldNotVerifyPasswordWhenPostPasswordIsNullButSubmittedIsNot() {
        Post post = new Post();
        post.password = null;

        assertFalse(postAccessService.verifyPassword(post, "somepassword"));
    }

    @Test
    void shouldVerifyEmptyPasswordWhenBothAreEmpty() {
        Post post = new Post();
        post.password = "";

        assertTrue(postAccessService.verifyPassword(post, ""));
    }
}
