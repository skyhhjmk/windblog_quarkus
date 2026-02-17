package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class AiTaskConsumer {

    @Inject
    AiManager aiManager;

    @Incoming("ai-summary-tasks-in")
    @Transactional
    public CompletionStage<Void> consume(AiSummaryTask task) {
        return aiManager.summarize(task.content())
                .thenAccept(summaries -> updatePostAiSummary(task.postId(), summaries))
                .exceptionally(ex -> {
                    System.err
                            .println("Failed to process AI summary for post " + task.postId() + ": " + ex.getMessage());
                    return null;
                });
    }

    private void updatePostAiSummary(Long postId, Map<String, String> summaries) {
        Post post = Post.findById(postId);
        if (post != null) {
            post.aiSummary = summaries;
            post.persist();
        }
    }
}
