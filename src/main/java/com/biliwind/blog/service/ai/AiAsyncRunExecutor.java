package com.biliwind.blog.service.ai;

import io.smallrye.context.api.ManagedExecutorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs durable AI work off the caller thread with a clean CDI request context
 * and cleared transaction. Scheduling failures are reported through the same
 * callback as execution failures, so a committed QUEUED row can never linger.
 */
@ApplicationScoped
public class AiAsyncRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiAsyncRunExecutor.class);

    @Inject
    @ManagedExecutorConfig(cleared = { ThreadContext.CDI, ThreadContext.TRANSACTION })
    ManagedExecutor executor;

    public void submit(String label, Runnable body, Consumer<Throwable> onFailure) {
        try {
            executor.runAsync(() -> AiRequestContexts.runWithRequestContext(body))
                    .exceptionally(error -> {
                        log.warn("{} 失败", label, error);
                        AiRequestContexts.runWithRequestContext(() -> onFailure.accept(error));
                        return null;
                    });
        } catch (RejectedExecutionException exception) {
            log.warn("{} 调度失败", label, exception);
            onFailure.accept(exception);
        } catch (RuntimeException exception) {
            log.warn("{} 启动失败", label, exception);
            onFailure.accept(exception);
        }
    }

    public static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
