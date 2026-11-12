package com.biliwind.blog.service.ai;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import java.util.function.Supplier;

/** One place that activates the CDI request context for detached AI work. */
public final class AiRequestContexts {

    private AiRequestContexts() {
    }

    public static void runWithRequestContext(Runnable body) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            body.run();
        } finally {
            requestContext.terminate();
        }
    }

    public static <T> T callWithRequestContext(Supplier<T> body) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            return body.get();
        } finally {
            requestContext.terminate();
        }
    }
}
