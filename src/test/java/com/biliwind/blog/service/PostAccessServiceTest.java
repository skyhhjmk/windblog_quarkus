package com.biliwind.blog.service;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserPurchaseRecord;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostAccessService 单元测试
 * 测试文章访问服务
 */
@QuarkusTest
class PostAccessServiceTest {

    @Inject
    PostAccessService postAccessService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    WalletService walletService;

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
        post.password = passwordHasher.hash("secret123");

        assertTrue(postAccessService.verifyPassword(post, "secret123"));
    }

    @Test
    void shouldRejectOldPlainTextPasswordAfterMigrationBoundary() {
        Post post = new Post();
        post.password = "secret123";

        assertFalse(postAccessService.verifyPassword(post, "secret123"));
    }

    @Test
    void shouldNotVerifyIncorrectPassword() {
        Post post = new Post();
        post.password = passwordHasher.hash("secret123");

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

        assertFalse(postAccessService.verifyPassword(post, ""));
    }

    @Test
    void shouldKeepPasswordProtectedContentOutOfPreview() {
        String secretContent = "password-only-content-" + UUID.randomUUID();

        String preview = postAccessService.getPreviewOnlyContent(
                secretContent, 0, true, 123L, 0L, null);

        assertFalse(preview.contains(secretContent));
    }

    @Test
    @Transactional
    void shouldRejectLowerClientPriceAndRecordServerBlockPrice() {
        User user = createTestUser();
        walletService.addPoints(user.id, 1000L, "TEST", "测试充值");
        Post post = createPaidBlockPost(user);

        Executable lowerPricePurchase = new Executable() {
            @Override
            public void execute() {
                postAccessService.buyPost(user.id, post.id, 1L, "vip-block");
            }
        };
        assertThrows(jakarta.ws.rs.BadRequestException.class, lowerPricePurchase);
        assertFalse(postAccessService.hasPurchasedBlock(user.id, post.id, "vip-block"));

        postAccessService.buyPost(user.id, post.id, 100L, "vip-block");

        assertTrue(postAccessService.hasPurchasedBlock(user.id, post.id, "vip-block"));
        UserPurchaseRecord record = UserPurchaseRecord.find(
                "userId = ?1 and targetType = 'POST' and targetId = ?2 and targetBlockId = ?3",
                user.id,
                post.id,
                "vip-block"
        ).firstResult();
        assertEquals(100L, record.pointsPaid);
    }

    private User createTestUser() {
        String suffix = UUID.randomUUID().toString();
        User user = new User();
        user.username = "buyer-" + suffix;
        user.email = "buyer-" + suffix + "@example.com";
        user.password = passwordHasher.hash("password123");
        user.status = 1;
        user.persist();
        return user;
    }

    private Post createPaidBlockPost(User user) {
        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "paid-post-" + UUID.randomUUID();
        post.title = Map.of("zh-cn", "付费文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = user;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn", "[hide-text id=vip-block price=100]secret[/hide-text]");
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = user;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        return post;
    }

    @Test
    @Transactional
    void shouldReturnFreeBlocksWithoutPurchase() {
        User user = createTestUser();
        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "free-block-post-" + UUID.randomUUID();
        post.title = Map.of("zh-cn", "免费文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = user;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn", "[hide-text id=free-block price=0]free content[/hide-text] [hide-text id=paid-block price=10]paid content[/hide-text]");
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = user;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;

        User reader = createTestUser();
        
        java.util.Map<String, String> unlocked = postAccessService.getUnlockedBlocks(
                "[hide-text id=free-block price=0]free content[/hide-text] [hide-text id=paid-block price=10]paid content[/hide-text]",
                -1L, false, post.id, 10L, reader.id
        );

        assertTrue(unlocked.containsKey("free-block"));
        assertEquals("free content", unlocked.get("free-block"));
        assertFalse(unlocked.containsKey("paid-block"));
    }
}
