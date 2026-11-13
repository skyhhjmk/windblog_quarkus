package com.biliwind.blog.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestFilter;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request filters run in ascending effective-priority order (JAX-RS spec); a
 * filter's effective priority is its {@code @Priority} value, or
 * {@link Priorities#USER} when it carries none. Some request filters read a
 * {@code @Context} value that another request filter writes first, so the
 * writer must not be sorted after the reader. Those ordering edges are invisible
 * in the annotations alone, so this test pins them: it is the one place the
 * stage contract is stated, and reordering a filter to violate it fails here
 * instead of shipping a path-bypass or stale-region bug.
 */
class FilterOrderingTest {

    private static final int DEFAULT_PRIORITY = Priorities.USER;

    @Test
    void regionWriterRunsBeforeEveryRegionReader() {
        assertRunsBefore(requestFilter(RegionFilter.class), List.of(
                requestFilter(LangRequestFilter.class)));
    }

    @Test
    void securityPreMatchingGuardsAreNotDemotedToPostRouting() {
        // 16f619d moved the admin encoded-path guard before routing: UriInfo
        // normalises %2e%2e and %2f, so reading the raw Vert.x path must happen
        // preMatching or the protection silently no-ops.
        assertTrue(isPreMatching(AdminPathGuardFilter.class),
                "AdminPathGuardFilter must run preMatching to see the raw path");
        assertTrue(isPreMatching(HoneypotRequestFilter.class),
                "HoneypotRequestFilter must run preMatching to gate every request");
    }

    private void assertRunsBefore(OrderedFilter writer, List<OrderedFilter> readers) {
        assertNotNull(writer, "writer filter not found as a request filter");
        for (OrderedFilter reader : readers) {
            assertNotNull(reader, "reader filter not found as a request filter");
            assertTrue(writer.priority() <= reader.priority(),
                    writer.type().getSimpleName() + " (priority " + writer.priority()
                            + ") must not be sorted after " + reader.type().getSimpleName()
                            + " (priority " + reader.priority() + ") that reads its output");
        }
    }

    private OrderedFilter requestFilter(Class<?> type) {
        if (!ContainerRequestFilter.class.isAssignableFrom(type)) {
            return null;
        }
        return new OrderedFilter(type, effectivePriority(type));
    }

    private int effectivePriority(Class<?> type) {
        Priority priority = type.getAnnotation(Priority.class);
        return priority == null ? DEFAULT_PRIORITY : priority.value();
    }

    private boolean isPreMatching(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                if (annotation.annotationType().getName().endsWith("ServerRequestFilter")) {
                    try {
                        Object flag = annotation.annotationType()
                                .getMethod("preMatching")
                                .invoke(annotation);
                        if (Boolean.TRUE.equals(flag)) {
                            return true;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        return false;
                    }
                }
            }
        }
        return type.isAnnotationPresent(jakarta.ws.rs.container.PreMatching.class);
    }

    private record OrderedFilter(Class<?> type, int priority) {
    }
}
