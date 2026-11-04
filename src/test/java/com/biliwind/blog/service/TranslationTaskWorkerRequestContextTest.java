package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.TranslationTask;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ai.AiSelectionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时任务 Drain 翻译队列时，异步工作线程不能继承调度线程已经结束的请求上下文，
 * 否则非事务的 Panache 读取（AI 提供商/系统设置）会抛 ContextNotActiveException，
 * 回退查找管理员时还会因 HQL 路径错误失败。任务必须使用自己新建的请求上下文。
 */
@QuarkusTest
class TranslationTaskWorkerRequestContextTest {

    @Inject
    TranslationTaskWorker worker;

    @Test
    void runsTaskWithoutInheritedSchedulerRequestContext() throws Exception {
        Fixture fixture = QuarkusTransaction.requiringNew().call(this::createFixture);

        AiSelectionService selection = new AiSelectionService() {
            @Override
            public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
                return fixture.provider();
            }

            @Override
            public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs,
                                             AiProviderConfig current) {
                return null;
            }
        };
        QuarkusMock.installMockForType(selection, AiSelectionService.class);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> worker.executeTask(fixture.taskId())).get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> result = QuarkusTransaction.requiringNew().call(() -> {
            TranslationTask task = TranslationTask.findById(fixture.taskId());
            Post post = Post.findById(fixture.postId());
            String english = post.title == null ? "" : post.title.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("en-US"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("");
            return Map.of("status", task.status,
                    "message", task.message == null ? "" : task.message,
                    "title", english);
        });

        assertEquals("SUCCEEDED", result.get("status"), "翻译任务应成功: " + result.get("message"));
        assertTrue(((String) result.get("title")).startsWith("AI Translation"),
                "应写入英文标题，实际: " + result.get("title"));
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        OffsetDateTime now = OffsetDateTime.now();

        User admin = new User();
        admin.username = "translation-worker-admin-" + suffix;
        admin.email = admin.username + "@example.com";
        admin.password = "not-used";
        admin.status = 1;
        admin.roleName = RoleConstant.ADMIN;
        admin.subscribeArticleUpdates = false;
        admin.subscribePromotions = false;
        admin.persist();

        Post post = new Post();
        post.slug = "translation-worker-post-" + suffix;
        post.title = Map.of("zh-cn", "翻译任务测试文章");
        post.summary = Map.of("zh-cn", "翻译任务测试摘要");
        post.status = PostStatus.DRAFT;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = admin;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn", "这是一段用于翻译任务测试的正文内容。");
        revision.editorType = 0;
        revision.revisionNumber = 1;
        revision.createdBy = admin;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        post.persist();

        AiProviderConfig provider = new AiProviderConfig();
        provider.type = AiConfigType.PROVIDER;
        provider.name = "translation-worker-mock-" + suffix;
        provider.provider = "MOCK";
        provider.enabled = true;
        provider.model = "mock";
        provider.persist();

        TranslationTask task = new TranslationTask();
        task.postId = post.id;
        task.targetLanguage = "en-US";
        task.sourceLanguage = "zh-cn";
        task.overwrite = true;
        task.status = "RUNNING";
        task.attempts = 0;
        task.maxAttempts = 5;
        task.nextAttemptAt = now;
        task.createdAt = now;
        task.updatedAt = now;
        task.createdBy = null;
        task.persist();

        return new Fixture(post.id, task.id, provider);
    }

    private record Fixture(Long postId, Long taskId, AiProviderConfig provider) {
    }
}
