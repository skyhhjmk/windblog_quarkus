package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link page used to scan the whole table on every request; the visible
 * list is now a region-scoped read model that a LINK sync event drops.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkPageCacheTest {

    @Inject
    CacheService cacheService;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    private String linkName;
    private Long linkId;

    @BeforeAll
    void seedVisibleLink() {
        linkName = "缓存友链-" + UUID.randomUUID();
        String url = "https://example.test/" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            OffsetDateTime now = OffsetDateTime.now();
            Link link = new Link();
            link.name = linkName;
            link.url = url;
            link.description = "缓存友链描述";
            link.sortOrder = 0;
            link.status = 1;
            link.applicationStatus = 1;
            link.availabilityStatus = "UNKNOWN";
            link.backlinkStatus = "UNKNOWN";
            link.target = "_blank";
            link.redirectType = 1;
            link.showUrl = true;
            link.type = LinkType.FRIENDLY_LINK;
            link.createdAt = now;
            link.updatedAt = now;
            link.persist();
            linkId = link.id;
            // A previous test class may have warmed the list before this row existed.
            dataSyncEvent.fire(new DataSyncEvent("LINK", link.id, "UPSERT"));
        });
    }

    @Test
    void linkPageIsServedFromTheRegionScopedCache() {
        given().when().get("/link")
                .then().statusCode(200)
                .body(containsString(linkName));

        assertTrue(cacheService.get(CacheService.Keys.publicLinks("global"),
                new TypeReference<List<PublicCacheRefreshService.PublicLinkItem>>() {}).isPresent());
    }

    @Test
    void linkSyncEventInvalidatesTheCachedList() {
        publicCacheRefreshService.findPublicLinks("global");
        assertTrue(cacheService.get(CacheService.Keys.publicLinks("global"),
                new TypeReference<List<PublicCacheRefreshService.PublicLinkItem>>() {}).isPresent());

        QuarkusTransaction.requiringNew().run(() ->
                dataSyncEvent.fire(new DataSyncEvent("LINK", linkId, "UPSERT")));

        assertTrue(cacheService.get(CacheService.Keys.publicLinks("global"),
                new TypeReference<List<PublicCacheRefreshService.PublicLinkItem>>() {}).isEmpty());
    }
}
