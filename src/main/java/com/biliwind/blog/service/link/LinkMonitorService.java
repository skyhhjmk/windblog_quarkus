package com.biliwind.blog.service.link;

import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkMonitorLog;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class LinkMonitorService {

    private final Client client = ClientBuilder.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Scheduled(every = "6h", identity = "link-monitor")
    @Transactional
    public void scheduleCheck() {
        checkAllLinks();
    }

    public void checkAllLinks() {
        List<Link> links = Link.listAll();
        for (Link link : links) {
            checkLink(link);
        }
    }

    @Transactional
    public void checkLink(Link link) {
        LinkMonitorLog log = new LinkMonitorLog();
        log.link = link;
        log.checkTime = OffsetDateTime.now();

        long start = System.currentTimeMillis();
        try (Response response = client.target(link.url).request().get()) {
            log.statusCode = response.getStatus();
            log.ok = (log.statusCode >= 200 && log.statusCode < 300);
            log.loadTimeMs = (int) (System.currentTimeMillis() - start);

            // Optional: Check for backlink in content
            if (log.ok) {
                String content = response.readEntity(String.class);
                log.backlinkFound = content.contains("biliwind.com"); // Replace with actual blog domain or config
            } else {
                log.backlinkFound = false;
            }
        } catch (Exception e) {
            log.ok = false;
            log.statusCode = 0;
            log.loadTimeMs = (int) (System.currentTimeMillis() - start);
            log.backlinkFound = false;
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("error", e.getMessage());
            log.rawData = errorData;
        }

        log.persist();

        // Update link status if multiple failures?
        // For now just logging.
    }
}
