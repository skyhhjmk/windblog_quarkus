package com.biliwind.blog.service.link;

import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.AdminLinkCheckJobItem;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkMonitorLog;
import com.biliwind.blog.model.LinkMonitorSource;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.RegionRule;
import com.biliwind.blog.service.EmailDeliveryService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import com.biliwind.blog.service.SiteLinkUrlService;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LinkMonitorService {

    private static final Logger LOG = Logger.getLogger(LinkMonitorService.class);
    private static final Duration JOB_RETENTION = Duration.ofMinutes(30);
    private static final Duration LOG_RETENTION = Duration.ofDays(90);

    @Inject
    SiteLinkUrlService siteLinkUrlService;

    @Inject
    EmailDeliveryService emailDeliveryService;

    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    LinkProbeService linkProbeService;

    @Inject
    DistributedLinkProbeService distributedLinkProbeService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Inject
    ManagedExecutor managedExecutor;

    private final Map<String, CheckJob> checkJobs = new ConcurrentHashMap<>();
    private final Map<Long, String> activeJobByLink = new ConcurrentHashMap<>();

    @Scheduled(every = "1m", identity = "link-monitor")
    public void scheduleCheck() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        try {
            checkAllLinks();
        } catch (Exception exception) {
            LOG.error("友链定时监控执行失败", exception);
        }
        try {
            advanceExpiredBuffers();
        } catch (Exception exception) {
            LOG.error("友链自动隐藏缓冲期处理失败", exception);
        }
    }

    @Scheduled(every = "24h", delayed = "5m", identity = "link-monitor-log-retention")
    public void cleanupExpiredLogs() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(LOG_RETENTION);
        QuarkusTransaction.requiringNew().run(() -> LinkMonitorLog.delete("checkTime < ?1", cutoff));
    }

    public void checkAllLinks() {
        List<Link> links = Link.list(
                "type = ?1 and applicationStatus = ?2",
                LinkType.FRIENDLY_LINK,
                (short) 1
        );
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Link link : links) {
            if (!LinkMonitorPolicy.isMonitoringEnabled(link)) {
                continue;
            }
            OffsetDateTime dueAt = now.minusMinutes(LinkMonitorPolicy.intervalMinutes(link));
            if (link.lastCheckedAt == null || !link.lastCheckedAt.isAfter(dueAt)) {
                enqueueCheck(link.id, LinkMonitorSource.AUTOMATIC);
            }
        }
    }

    /** Starts a manual or system-triggered check without holding the HTTP request open. */
    public synchronized AdminLinkCheckJobItem enqueueCheck(long linkId, LinkMonitorSource source) {
        pruneFinishedJobs();

        String activeJobId = activeJobByLink.get(linkId);
        if (activeJobId != null) {
            CheckJob activeJob = checkJobs.get(activeJobId);
            if (activeJob != null && "RUNNING".equals(activeJob.status)) {
                return activeJob.toItem();
            }
            activeJobByLink.remove(linkId, activeJobId);
        }

        String siteName = siteLinkUrlService.currentSiteName();
        LinkCheckTarget target = QuarkusTransaction.requiringNew().call(() -> {
            Link link = Link.findById(linkId);
            return link == null ? null : buildTarget(link);
        });
        if (target == null) {
            return new AdminLinkCheckJobItem(UUID.randomUUID().toString(), linkId, "FAILED", null, null,
                    "链接不存在");
        }
        CheckJob job = new CheckJob(UUID.randomUUID().toString(), linkId, OffsetDateTime.now(ZoneOffset.UTC));
        checkJobs.put(job.jobId, job);
        activeJobByLink.put(linkId, job.jobId);
        try {
            managedExecutor.execute(() -> executeJob(job, target, siteName, source));
        } catch (RuntimeException exception) {
            activeJobByLink.remove(linkId, job.jobId);
            job.finish("FAILED", "检测任务暂时无法启动，请稍后重试");
            LOG.errorf(exception, "无法提交友链检测任务，linkId=%d", linkId);
        }
        return job.toItem();
    }

    public AdminLinkCheckJobItem getCheckJob(long linkId, String jobId) {
        CheckJob job = checkJobs.get(jobId);
        if (job == null) {
            return new AdminLinkCheckJobItem(jobId, linkId, "UNKNOWN", null, null, null);
        }
        if (job.linkId != linkId) {
            return null;
        }
        return job.toItem();
    }

    public void checkLink(Link link, boolean readOnly) {
        LinkCheckTarget target = buildTarget(link);
        runCheck(target, siteLinkUrlService.currentSiteName(), readOnly,
                LinkMonitorSource.AUTOMATIC);
    }

    private void executeJob(CheckJob job, LinkCheckTarget target, String siteName,
                            LinkMonitorSource source) {
        try {
            runCheck(target, siteName, false, source);
            job.finish("COMPLETED", null);
        } catch (Exception exception) {
            LOG.errorf(exception, "友链检测任务失败，linkId=%d", job.linkId);
            job.finish("FAILED", "检测执行失败，请稍后重试");
        } finally {
            activeJobByLink.remove(job.linkId, job.jobId);
        }
    }

    private void pruneFinishedJobs() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(JOB_RETENTION);
        checkJobs.entrySet().removeIf(entry -> {
            CheckJob job = entry.getValue();
            return !"RUNNING".equals(job.status) && job.completedAt != null && job.completedAt.isBefore(cutoff);
        });
    }

    private void runCheck(LinkCheckTarget target, String siteName, boolean readOnly,
                          LinkMonitorSource source) {
        String checkBatchId = UUID.randomUUID().toString();
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<NodeLinkProbeResult> nodeResults = new ArrayList<>();
        LinkProbeResult primaryResult = linkProbeService.probe(
                target.url(), target.expectedSiteUrls(), siteName, target.monitoringKeywords(), target.name());
        nodeResults.add(new NodeLinkProbeResult("main", "主节点", primaryResult));
        nodeResults.addAll(distributedLinkProbeService.probeOnlineEdgeNodes(
                target.url(), target.expectedSiteUrls(), siteName, target.monitoringKeywords(), target.name()));

        persistLogAndState(target.id(), checkBatchId, checkedAt, nodeResults, readOnly, source);
    }

    private void persistLogAndState(
            long linkId,
            String checkBatchId,
            OffsetDateTime checkedAt,
            List<NodeLinkProbeResult> nodeResults,
            boolean readOnly,
            LinkMonitorSource source
    ) {
        QuarkusTransaction.requiringNew().run(() -> {
            Link dbLink = Link.findById(linkId);
            if (dbLink == null) {
                return;
            }

            for (NodeLinkProbeResult nodeResult : nodeResults) {
                saveMonitorLog(dbLink, checkBatchId, checkedAt, nodeResult, source);
            }

            if (!readOnly) {
                updateLinkMonitoringState(dbLink, checkedAt, nodeResults);
                dbLink.persist();
                dataSyncEvent.fire(new DataSyncEvent("LINK", dbLink.id, "UPSERT"));
            }
        });
    }

    private void saveMonitorLog(
            Link link,
            String checkBatchId,
            OffsetDateTime checkedAt,
            NodeLinkProbeResult nodeResult,
            LinkMonitorSource source
    ) {
        LinkProbeResult probeResult = nodeResult.probeResult();
        LinkMonitorLog monitorLog = new LinkMonitorLog();
        monitorLog.link = link;
        monitorLog.checkTime = checkedAt;
        monitorLog.checkSource = source;
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
        rawData.put("checkSource", source.name());
        if (probeResult.errorMessage() != null && !probeResult.errorMessage().isBlank()) {
            rawData.put("error", probeResult.errorMessage());
        }
        if (probeResult.evidence() != null) {
            rawData.put("detectionDetails", probeResult.evidence().toMap());
            rawData.put("keywordFraudDetected", probeResult.evidence().keywordFraudDetected());
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
        boolean fraudDetectedFromReachableNode = false;
        boolean detectorAvailableOnReachableNode = false;

        for (NodeLinkProbeResult nodeResult : nodeResults) {
            LinkProbeResult probeResult = nodeResult.probeResult();
            if (probeResult.reachable()) {
                reachableFromAnyNode = true;
                boolean detectorSupported = probeResult.evidence() != null
                        && probeResult.evidence().detectorSupported();
                if (detectorSupported) {
                    detectorAvailableOnReachableNode = true;
                }
                if (detectorSupported && probeResult.backlinkFound()) {
                    backlinkFoundFromReachableNode = true;
                }
                if (detectorSupported && probeResult.evidence().keywordFraudDetected()) {
                    fraudDetectedFromReachableNode = true;
                }
            }
        }

        if (reachableFromAnyNode) {
            link.availabilityStatus = "ONLINE";
            if (detectorAvailableOnReachableNode) {
                link.backlinkStatus = backlinkFoundFromReachableNode ? "FOUND" : "MISSING";
            } else {
                link.backlinkStatus = "UNKNOWN";
            }
        } else {
            link.availabilityStatus = "OFFLINE";
            link.backlinkStatus = "UNKNOWN";
        }

        link.lastCheckedAt = checkedAt;
        if (fraudDetectedFromReachableNode) {
            link.keywordFraudStatus = "DETECTED";
        } else if (detectorAvailableOnReachableNode) {
            link.keywordFraudStatus = "CLEAN";
        }
        if (link.settings == null) {
            link.settings = new HashMap<>();
        }
        link.settings.put("lastCheckTime", checkedAt.toString());
        link.settings.put("isAvailable", reachableFromAnyNode);
        link.settings.put("backlinkFound", backlinkFoundFromReachableNode);
        link.settings.put("checkedNodeCount", nodeResults.size());

        OffsetDateTime evaluatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        advanceCondition(link, LinkMonitorPolicy.OFFLINE, !reachableFromAnyNode, evaluatedAt);
        advanceCondition(link, LinkMonitorPolicy.BACKLINK_MISSING,
                reachableFromAnyNode && detectorAvailableOnReachableNode && !backlinkFoundFromReachableNode,
                evaluatedAt);
        advanceCondition(link, LinkMonitorPolicy.KEYWORD_FRAUD,
                reachableFromAnyNode && fraudDetectedFromReachableNode, evaluatedAt);
    }

    private void advanceCondition(Link link, String condition, boolean failed, OffsetDateTime checkedAt) {
        Map<String, Object> state = LinkMonitorPolicy.lifecycleState(link, condition);
        if (!LinkMonitorPolicy.isHideEnabled(link, condition) || !failed) {
            state.clear();
            state.put("phase", "ACTIVE");
            state.put("consecutiveFailures", 0);
            LinkMonitorPolicy.updateLifecycleState(link, condition, state);
            return;
        }

        int failures = numberValue(state.get("consecutiveFailures")) + 1;
        state.put("consecutiveFailures", failures);
        state.put("lastFailureAt", checkedAt.toString());
        if (!"BUFFERING".equals(state.get("phase")) && !"HIDDEN".equals(state.get("phase"))
                && failures >= LinkMonitorPolicy.REQUIRED_CONSECUTIVE_FAILURES) {
            OffsetDateTime graceEndsAt = checkedAt.plusDays(LinkMonitorPolicy.graceDays(link, condition));
            state.put("phase", "BUFFERING");
            state.put("bufferStartedAt", checkedAt.toString());
            state.put("graceEndsAt", graceEndsAt.toString());
            if (queueNotification(link, condition, false)) {
                state.put("warningNotified", true);
            }
        } else if ("BUFFERING".equals(state.get("phase"))
                && !Boolean.TRUE.equals(state.get("warningNotified"))
                && queueNotification(link, condition, false)) {
            state.put("warningNotified", true);
        }
        expireConditionIfDue(link, condition, state, checkedAt);
        LinkMonitorPolicy.updateLifecycleState(link, condition, state);
    }

    private void advanceExpiredBuffers() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        List<Long> ids = Link.<Link>list("type = ?1 and applicationStatus = ?2",
                        LinkType.FRIENDLY_LINK, (short) 1)
                .stream().map(link -> link.id).toList();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Long linkId : ids) {
            QuarkusTransaction.requiringNew().run(() -> {
                Link link = Link.findById(linkId);
                if (link == null) {
                    return;
                }
                boolean changed = false;
                for (String condition : List.of(LinkMonitorPolicy.OFFLINE, LinkMonitorPolicy.BACKLINK_MISSING,
                        LinkMonitorPolicy.KEYWORD_FRAUD)) {
                    Map<String, Object> state = LinkMonitorPolicy.lifecycleState(link, condition);
                    boolean conditionChanged = false;
                    if ("BUFFERING".equals(state.get("phase"))
                            && !Boolean.TRUE.equals(state.get("warningNotified"))
                            && queueNotification(link, condition, false)) {
                        state.put("warningNotified", true);
                        conditionChanged = true;
                    }
                    if (expireConditionIfDue(link, condition, state, now)) {
                        conditionChanged = true;
                    }
                    if (conditionChanged) {
                        LinkMonitorPolicy.updateLifecycleState(link, condition, state);
                        changed = true;
                    }
                }
                if (changed) {
                    link.persist();
                    dataSyncEvent.fire(new DataSyncEvent("LINK", link.id, "UPSERT"));
                }
            });
        }
    }

    private boolean expireConditionIfDue(Link link, String condition, Map<String, Object> state,
                                         OffsetDateTime now) {
        if (!"BUFFERING".equals(state.get("phase"))) {
            return false;
        }
        OffsetDateTime graceEndsAt = LinkMonitorPolicy.graceEndsAt(state);
        if (graceEndsAt == null || graceEndsAt.isAfter(now)) {
            return false;
        }
        boolean notificationRequired = LinkMonitorPolicy.shouldNotify(link, condition)
                && link.email != null && !link.email.isBlank();
        if (notificationRequired && !Boolean.TRUE.equals(state.get("formalNotified"))
                && !queueNotification(link, condition, true)) {
            return false;
        }
        state.put("phase", "HIDDEN");
        state.put("hiddenAt", now.toString());
        if (notificationRequired && !Boolean.TRUE.equals(state.get("formalNotified"))) {
            state.put("formalNotified", true);
        }
        return true;
    }

    private boolean queueNotification(Link link, String condition, boolean formal) {
        if (!LinkMonitorPolicy.shouldNotify(link, condition) || link.email == null || link.email.isBlank()) {
            return false;
        }
        BlogRegion region = LinkMonitorPolicy.displayRegion(link);
        String siteUrl = notificationSiteUrl(region);
        String conditionLabel = switch (condition) {
            case LinkMonitorPolicy.OFFLINE -> "无法访问";
            case LinkMonitorPolicy.BACKLINK_MISSING -> "未检测到反链";
            default -> "检测到关键词欺诈";
        };
        String status = formal ? "已超过缓冲期，友链现已自动隐藏。"
                : "连续 3 次检测到此问题，友链将在缓冲期内继续展示。";
        String subject = formal ? "WindBlog 友链已自动隐藏" : "WindBlog 友链检测异常提醒";
        String body = "友链名称：" + link.name + "\n友链地址：" + link.url + "\n异常状态：" + conditionLabel
                + "\n" + status;
        String html = emailTemplateRenderer.render(
                formal ? "友链已自动隐藏" : "友链检测异常提醒",
                "您好，友链站长：",
                body,
                "访问本站",
                siteUrl);
        try {
            emailDeliveryService.queueRenderedHtml("FRIEND_LINK_MONITOR", link.email, subject, html);
            return true;
        } catch (RuntimeException ignored) {
            LOG.warnf("友链通知暂时无法入队，linkId=%d condition=%s formal=%s",
                    link.id, condition, formal);
            return false;
        }
    }

    private String notificationSiteUrl(BlogRegion region) {
        List<String> regionalUrls = regionRuleSiteUrls(region);
        return regionalUrls.isEmpty() ? siteLinkUrlService.urlFor(region) : regionalUrls.get(0);
    }

    private LinkCheckTarget buildTarget(Link link) {
        BlogRegion region = LinkMonitorPolicy.displayRegion(link);
        List<String> regionalUrls = regionRuleSiteUrls(region);
        List<String> manualUrls = LinkMonitorPolicy.backlinkCheckUrls(link);
        List<String> expectedUrls = manualUrls.isEmpty()
                ? (regionalUrls.isEmpty() ? List.of(siteLinkUrlService.urlFor(region)) : regionalUrls)
                : manualUrls;
        return new LinkCheckTarget(link.id, link.url, link.name, LinkMonitorPolicy.monitoringKeywords(link),
                List.copyOf(expectedUrls));
    }

    private List<String> regionRuleSiteUrls(BlogRegion region) {
        List<RegionRule> rules = RegionRule.<RegionRule>list("region = ?1 and isEnabled = true order by priority desc, id asc",
                region);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (RegionRule rule : rules) {
            if (rule.ruleType == null || !"domain".equalsIgnoreCase(rule.ruleType.trim())) {
                continue;
            }
            String domain = normalizeRuleDomain(rule.pattern);
            if (domain.isBlank()) {
                continue;
            }
            urls.add("https://" + domain);
            urls.add("http://" + domain);
        }
        return List.copyOf(urls);
    }

    private String normalizeRuleDomain(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "";
        }
        String candidate = pattern.trim();
        if (candidate.startsWith("*.")) {
            candidate = candidate.substring(2);
        }
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                return "";
            }
            String domain = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (uri.getPort() >= 0) {
                domain += ":" + uri.getPort();
            }
            while (domain.endsWith(".")) {
                domain = domain.substring(0, domain.length() - 1);
            }
            return domain;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value.toString()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record LinkCheckTarget(Long id, String url, String name, String monitoringKeywords,
                                   List<String> expectedSiteUrls) {
    }

    private static final class CheckJob {
        private final String jobId;
        private final long linkId;
        private final OffsetDateTime startedAt;
        private volatile String status = "RUNNING";
        private volatile OffsetDateTime completedAt;
        private volatile String errorMessage;

        private CheckJob(String jobId, long linkId, OffsetDateTime startedAt) {
            this.jobId = jobId;
            this.linkId = linkId;
            this.startedAt = startedAt;
        }

        private void finish(String status, String errorMessage) {
            this.errorMessage = errorMessage;
            this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            this.status = status;
        }

        private AdminLinkCheckJobItem toItem() {
            return new AdminLinkCheckJobItem(jobId, linkId, status, startedAt, completedAt, errorMessage);
        }
    }
}
