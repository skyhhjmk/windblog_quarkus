package com.biliwind.blog.service.link;

import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkMonitorLog;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.service.PublicUrlService;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@ApplicationScoped
public class LinkMonitorService {

    private static final Logger LOG = Logger.getLogger(LinkMonitorService.class);

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    LinkProbeService linkProbeService;

    @Inject
    DistributedLinkProbeService distributedLinkProbeService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Scheduled(every = "6h", identity = "link-monitor")
    public void scheduleCheck() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        try {
            checkAllLinks();
        } catch (Exception exception) {
            LOG.error("友链定时监控执行失败", exception);
        }
    }

    public void checkAllLinks() {
        List<Link> links = Link.list(
                "type = ?1 and applicationStatus = ?2",
                LinkType.FRIENDLY_LINK,
                (short) 1
        );
        for (Link link : links) {
            checkLink(link, false);
        }
    }

    public void checkLink(Link link, boolean readOnly) {
        String siteUrl = publicUrlService.getBaseUrl();
        String checkBatchId = UUID.randomUUID().toString();
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);

        List<NodeLinkProbeResult> nodeResults = new ArrayList<>();
        LinkProbeResult primaryResult = linkProbeService.probe(link.url, siteUrl);
        nodeResults.add(new NodeLinkProbeResult("main", "主节点", primaryResult));
        nodeResults.addAll(distributedLinkProbeService.probeOnlineEdgeNodes(link.url, siteUrl));

        persistLogAndState(link, checkBatchId, checkedAt, nodeResults, readOnly);
    }

    @Transactional
    public void persistLogAndState(
            Link link,
            String checkBatchId,
            OffsetDateTime checkedAt,
            List<NodeLinkProbeResult> nodeResults,
            boolean readOnly
    ) {
        Link dbLink = Link.findById(link.id);
        if (dbLink == null) {
            return;
        }

        for (NodeLinkProbeResult nodeResult : nodeResults) {
            saveMonitorLog(dbLink, checkBatchId, checkedAt, nodeResult);
        }

        if (!readOnly) {
            updateLinkMonitoringState(dbLink, checkedAt, nodeResults);
            dbLink.persist();
            dataSyncEvent.fire(new DataSyncEvent("LINK", dbLink.id, "UPSERT"));
        }
    }

    private void saveMonitorLog(
            Link link,
            String checkBatchId,
            OffsetDateTime checkedAt,
            NodeLinkProbeResult nodeResult
    ) {
        LinkProbeResult probeResult = nodeResult.probeResult();
        LinkMonitorLog monitorLog = new LinkMonitorLog();
        monitorLog.link = link;
        monitorLog.checkTime = checkedAt;
        monitorLog.checkBatchId = checkBatchId;
        monitorLog.nodeId = nodeResult.nodeId();
        monitorLog.nodeName = nodeResult.nodeName();
        monitorLog.ok = probeResult.reachable();
        monitorLog.loadTimeMs = probeResult.loadTimeMs();
        monitorLog.backlinkFound = probeResult.backlinkFound();
        monitorLog.statusCode = probeResult.statusCode();
        monitorLog.errorMessage = probeResult.errorMessage();

        Map<String, Object> rawData = new HashMap<>();
        rawData.put("nodeId", nodeResult.nodeId());
        rawData.put("nodeName", nodeResult.nodeName());
        if (probeResult.errorMessage() != null && !probeResult.errorMessage().isBlank()) {
            rawData.put("error", probeResult.errorMessage());
        }
        monitorLog.rawData = rawData;
        monitorLog.persist();
    }

    private void updateLinkMonitoringState(
            Link link,
            OffsetDateTime checkedAt,
            List<NodeLinkProbeResult> nodeResults
    ) {
        boolean reachableFromAnyNode = false;
        boolean backlinkFoundFromReachableNode = false;

        for (NodeLinkProbeResult nodeResult : nodeResults) {
            LinkProbeResult probeResult = nodeResult.probeResult();
            if (probeResult.reachable()) {
                reachableFromAnyNode = true;
                if (probeResult.backlinkFound()) {
                    backlinkFoundFromReachableNode = true;
                }
            }
        }

        if (reachableFromAnyNode) {
            link.availabilityStatus = "ONLINE";
            if (backlinkFoundFromReachableNode) {
                link.backlinkStatus = "FOUND";
            } else {
                link.backlinkStatus = "MISSING";
            }
        } else {
            link.availabilityStatus = "OFFLINE";
            link.backlinkStatus = "UNKNOWN";
        }

        link.lastCheckedAt = checkedAt;
        if (link.settings == null) {
            link.settings = new HashMap<>();
        }
        link.settings.put("lastCheckTime", checkedAt.toString());
        link.settings.put("isAvailable", reachableFromAnyNode);
        link.settings.put("backlinkFound", backlinkFoundFromReachableNode);
        link.settings.put("checkedNodeCount", nodeResults.size());
    }
}
