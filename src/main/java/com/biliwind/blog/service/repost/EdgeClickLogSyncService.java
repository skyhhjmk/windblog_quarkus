package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.AffiliateToken;
import com.biliwind.blog.model.RedirectClickEvent;
import com.biliwind.blog.model.RepostLicense;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 边缘点击日志回传的本地幂等合并入口。
 */
@ApplicationScoped
public class EdgeClickLogSyncService {

    @Scheduled(every = "5m", delayed = "1m")
    void markPrimaryLocalEventsAsSynced() {
        markSmallBatchAsSynced();
    }

    @Transactional
    public int markSmallBatchAsSynced() {
        List<RedirectClickEvent> clickEvents = RedirectClickEvent.find(
                "syncedToPrimary = false order by clickedAt asc"
        ).page(0, 100).list();

        int changedCount = 0;
        for (RedirectClickEvent clickEvent : clickEvents) {
            clickEvent.syncedToPrimary = true;
            changedCount = changedCount + 1;
        }
        return changedCount;
    }

    @Transactional
    public boolean mergeClickEvent(ClickEventMergeRequest request) {
        if (request == null) {
            return false;
        }
        if (request.eventKey == null || request.eventKey.isBlank()) {
            return false;
        }
        if (RedirectClickEvent.count("eventKey = ?1", request.eventKey) > 0) {
            return false;
        }

        AffiliateToken affiliateToken = AffiliateToken.findById(request.affiliateTokenId);
        if (affiliateToken == null) {
            return false;
        }

        RedirectClickEvent clickEvent = new RedirectClickEvent();
        clickEvent.eventKey = request.eventKey;
        clickEvent.affiliateToken = affiliateToken;
        if (request.repostLicenseId != null) {
            RepostLicense repostLicense = RepostLicense.findById(request.repostLicenseId);
            clickEvent.repostLicense = repostLicense;
        }
        clickEvent.refererUrl = request.refererUrl;
        clickEvent.refererDomain = request.refererDomain;
        clickEvent.refererCategory = request.refererCategory;
        clickEvent.ipHash = request.ipHash;
        clickEvent.userAgentHash = request.userAgentHash;
        clickEvent.deviceRiskId = request.deviceRiskId;
        clickEvent.riskScore = request.riskScore;
        clickEvent.sourceNodeId = request.sourceNodeId;
        clickEvent.clickedAt = request.clickedAt;
        if (clickEvent.clickedAt == null) {
            clickEvent.clickedAt = OffsetDateTime.now();
        }
        clickEvent.syncedToPrimary = true;
        clickEvent.persist();
        return true;
    }

    public static class ClickEventMergeRequest {
        public String eventKey;
        public Long affiliateTokenId;
        public Long repostLicenseId;
        public String refererUrl;
        public String refererDomain;
        public String refererCategory;
        public String ipHash;
        public String userAgentHash;
        public String deviceRiskId;
        public int riskScore;
        public String sourceNodeId;
        public OffsetDateTime clickedAt;
    }
}
