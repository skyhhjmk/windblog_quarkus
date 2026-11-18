package com.biliwind.blog.controller;

import com.biliwind.blog.service.PostViewService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostViewApiControllerTest {

    private PostViewApiController controller;
    private Map<String, Long> store;
    private Set<String> rateKeys;

    @BeforeEach
    void setup() throws Exception {
        controller = new PostViewApiController();
        store = new HashMap<>();
        store.put("hello-world", 10L);
        store.put("second-post", 25L);
        rateKeys = new HashSet<>();

        PostViewService viewService = new PostViewService() {
            @Override
            public Optional<Long> recordView(String slug, String clientIp) {
                if (slug == null || slug.isBlank() || !store.containsKey(slug.trim())) {
                    return Optional.empty();
                }
                String clean = slug.trim();
                String key = "post-view:" + clean + ":" + (clientIp == null ? "" : clientIp);
                if (rateKeys.add(key)) {
                    store.put(clean, store.get(clean) + 1L);
                }
                return Optional.of(store.get(clean));
            }

            @Override
            public Map<String, Long> getViewCounts(Collection<String> slugs) {
                Map<String, Long> result = new LinkedHashMap<>();
                for (String slug : slugs) {
                    if (store.containsKey(slug)) {
                        result.put(slug, store.get(slug));
                    }
                }
                return result;
            }
        };

        setField(controller, "postViewService", viewService);
        setField(controller, "clientIpResolver", new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(io.vertx.ext.web.RoutingContext context) {
                return new ClientIpResolution("127.0.0.1", "127.0.0.1", null, false, null);
            }
        });
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void shouldIncrementViewOncePerWindowAndReturnCurrentCount() {
        Response first = controller.recordView("hello-world", null);
        assertEquals(200, first.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstEntity = (Map<String, Object>) first.getEntity();
        assertTrue((Boolean) firstEntity.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) firstEntity.get("data");
        assertEquals("hello-world", firstData.get("slug"));
        assertEquals(11L, firstData.get("viewCount"));

        Response second = controller.recordView("hello-world", null);
        assertEquals(200, second.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> secondEntity = (Map<String, Object>) second.getEntity();
        @SuppressWarnings("unchecked")
        Map<String, Object> secondData = (Map<String, Object>) secondEntity.get("data");
        assertEquals(11L, secondData.get("viewCount"));
    }

    @Test
    void shouldRejectBlankOrMissingPostSlug() {
        assertThrows(BadRequestException.class, () -> controller.recordView("  ", null));
        assertThrows(NotFoundException.class, () -> controller.recordView("non-existent", null));
    }

    @Test
    void shouldReturnBatchViewCounts() {
        Response empty = controller.getViews(" ");
        assertEquals(200, empty.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> emptyEntity = (Map<String, Object>) empty.getEntity();
        assertTrue(((Map<?, ?>) emptyEntity.get("data")).isEmpty());

        Response batch = controller.getViews("hello-world, second-post, missing");
        assertEquals(200, batch.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> batchEntity = (Map<String, Object>) batch.getEntity();
        @SuppressWarnings("unchecked")
        Map<String, Long> data = (Map<String, Long>) batchEntity.get("data");
        assertEquals(10L, data.get("hello-world"));
        assertEquals(25L, data.get("second-post"));
        assertEquals(2, data.size());
    }
}
