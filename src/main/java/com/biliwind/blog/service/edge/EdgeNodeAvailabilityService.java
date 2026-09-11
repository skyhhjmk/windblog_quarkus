package com.biliwind.blog.service.edge;

import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.model.EdgeNodeAvailabilitySample;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EdgeNodeAvailabilityService {

    private static final long ONLINE_TIMEOUT_SECONDS = 90L;
    private static final int MAX_CHART_POINTS = 240;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    PrimaryEdgeChannelRegistry primaryEdgeChannelRegistry;

    @Scheduled(every = "60s")
    @Transactional
    public void sampleAvailability() {
        if (!nodeRoleService.isPrimaryNode()) {
            return;
        }

        OffsetDateTime sampledAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<EdgeNode> nodes = EdgeNode.listAll();
        for (EdgeNode node : nodes) {
            if (!Boolean.TRUE.equals(node.isEnabled)) {
                continue;
            }

            EdgeNodeAvailabilitySample sample = new EdgeNodeAvailabilitySample();
            sample.nodeId = node.nodeId;
            sample.sampledAt = sampledAt;
            sample.online = isCurrentlyOnline(node, sampledAt);
            sample.latencyMs = sample.online ? calculateHeartbeatLatencyMs(node, sampledAt) : null;
            sample.persist();
        }

        deleteExpiredSamples(sampledAt);
    }

    public AvailabilityRates calculateRates(String nodeId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AvailabilityRates(
                calculateRate(nodeId, now.minusHours(1)),
                calculateRate(nodeId, now.minusHours(24)),
                calculateRate(nodeId, now.minusDays(7)),
                calculateRate(nodeId, now.minusDays(30))
        );
    }

    public AvailabilityRates calculateRates(String nodeId, List<EdgeNodeAvailabilitySample> samples) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AvailabilityRates(
                calculateRateFromMemory(samples, now.minusHours(1)),
                calculateRateFromMemory(samples, now.minusHours(24)),
                calculateRateFromMemory(samples, now.minusDays(7)),
                calculateRateFromMemory(samples, now.minusDays(30))
        );
    }

    /**
     * Use the same liveness definition for the current status endpoint and
     * historical samples. WESP has no gRPC channel in the primary process, so
     * checking the channel registry alone makes every WESP sample offline.
     */
    public boolean isCurrentlyOnline(EdgeNode node, OffsetDateTime now) {
        if (node == null || !Boolean.TRUE.equals(node.isEnabled)) {
            return false;
        }
        if (node.connectionType == com.biliwind.blog.model.EdgeConnectionType.WESP) {
            return "ONLINE".equals(node.status)
                    && isHeartbeatFresh(node.lastHeartbeat, now);
        }
        return primaryEdgeChannelRegistry.hasOnlineChannel(node.nodeId);
    }

    private boolean isHeartbeatFresh(OffsetDateTime heartbeatAt, OffsetDateTime now) {
        if (heartbeatAt == null || now == null || heartbeatAt.isAfter(now)) {
            return false;
        }
        return java.time.Duration.between(heartbeatAt, now).toMillis()
                <= ONLINE_TIMEOUT_SECONDS * 1000L;
    }

    private Long calculateHeartbeatLatencyMs(EdgeNode node, OffsetDateTime now) {
        if (node.lastHeartbeat == null || node.lastHeartbeat.isAfter(now)) {
            return null;
        }
        return Long.valueOf(Math.max(0L,
                java.time.Duration.between(node.lastHeartbeat, now).toMillis()));
    }

    private AvailabilityRate calculateRateFromMemory(List<EdgeNodeAvailabilitySample> samples, OffsetDateTime since) {
        long totalSamples = 0;
        long onlineSamples = 0;
        for (EdgeNodeAvailabilitySample sample : samples) {
            if (sample.sampledAt.isAfter(since) || sample.sampledAt.isEqual(since)) {
                totalSamples = totalSamples + 1;
                if (sample.online) {
                    onlineSamples = onlineSamples + 1;
                }
            }
        }
        if (totalSamples == 0) {
            return new AvailabilityRate(null, 0L, 0L);
        }
        double onlineRate = (double) onlineSamples * 100.0D / (double) totalSamples;
        return new AvailabilityRate(Double.valueOf(onlineRate), totalSamples, onlineSamples);
    }

    public AvailabilityHistory getHistory(String nodeId, int days) {
        int safeDays = days;
        if (safeDays <= 0) {
            safeDays = 30;
        }
        if (safeDays > 30) {
            safeDays = 30;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime since = now.minusDays(safeDays);
        List<EdgeNodeAvailabilitySample> samples = EdgeNodeAvailabilitySample.list(
                "nodeId = ?1 and sampledAt >= ?2 order by sampledAt asc",
                nodeId,
                since
        );

        List<AvailabilitySamplePoint> points = buildSamplePoints(samples);
        List<AvailabilityOnlinePeriod> onlinePeriods = buildOnlinePeriods(samples, now);
        List<AvailabilityCalendarDay> calendarDays = buildCalendarDays(samples, since, now);
        AvailabilityRates rates = calculateRates(nodeId, samples);

        return new AvailabilityHistory(nodeId, since, now, rates, points, onlinePeriods, calendarDays);
    }

    private AvailabilityRate calculateRate(String nodeId, OffsetDateTime since) {
        long totalSamples = EdgeNodeAvailabilitySample.count(
                "nodeId = ?1 and sampledAt >= ?2",
                nodeId,
                since
        );
        if (totalSamples == 0) {
            return new AvailabilityRate(null, 0L, 0L);
        }

        long onlineSamples = EdgeNodeAvailabilitySample.count(
                "nodeId = ?1 and sampledAt >= ?2 and online = true",
                nodeId,
                since
        );
        double onlineRate = onlineSamples * 100.0D / totalSamples;
        return new AvailabilityRate(Double.valueOf(onlineRate), totalSamples, onlineSamples);
    }

    private void deleteExpiredSamples(OffsetDateTime now) {
        OffsetDateTime expiredBefore = now.minusDays(31);
        EdgeNodeAvailabilitySample.delete("sampledAt < ?1", expiredBefore);
    }

    private List<AvailabilitySamplePoint> buildSamplePoints(List<EdgeNodeAvailabilitySample> samples) {
        List<AvailabilitySamplePoint> points = new ArrayList<>();
        if (samples.size() <= MAX_CHART_POINTS) {
            for (EdgeNodeAvailabilitySample sample : samples) {
                points.add(new AvailabilitySamplePoint(sample.sampledAt, sample.online, sample.latencyMs));
            }
            return points;
        }

        // Keep the raw samples for rate calculations, but send a compact
        // representative point to the admin chart (about one point per 3h at
        // the default 30-day range). The last point of each bucket preserves
        // the most recent state and latency in that bucket.
        int bucketSize = (int) Math.ceil((double) samples.size() / MAX_CHART_POINTS);
        for (int start = 0; start < samples.size(); start += bucketSize) {
            int end = Math.min(samples.size(), start + bucketSize);
            EdgeNodeAvailabilitySample sample = samples.get(end - 1);
            points.add(new AvailabilitySamplePoint(sample.sampledAt, sample.online, sample.latencyMs));
        }
        return points;
    }

    private List<AvailabilityOnlinePeriod> buildOnlinePeriods(List<EdgeNodeAvailabilitySample> samples,
                                                              OffsetDateTime now) {
        List<AvailabilityOnlinePeriod> onlinePeriods = new ArrayList<>();
        OffsetDateTime currentOnlineStart = null;
        OffsetDateTime lastOnlineSampleTime = null;

        for (EdgeNodeAvailabilitySample sample : samples) {
            if (sample.online) {
                if (currentOnlineStart == null) {
                    currentOnlineStart = sample.sampledAt;
                }
                lastOnlineSampleTime = sample.sampledAt;
                continue;
            }

            if (currentOnlineStart != null) {
                onlinePeriods.add(new AvailabilityOnlinePeriod(currentOnlineStart, sample.sampledAt));
                currentOnlineStart = null;
                lastOnlineSampleTime = null;
            }
        }

        if (currentOnlineStart != null) {
            OffsetDateTime endAt = now;
            if (lastOnlineSampleTime != null && lastOnlineSampleTime.plusMinutes(2).isBefore(now)) {
                endAt = lastOnlineSampleTime.plusMinutes(1);
            }
            onlinePeriods.add(new AvailabilityOnlinePeriod(currentOnlineStart, endAt));
        }

        return onlinePeriods;
    }

    private List<AvailabilityCalendarDay> buildCalendarDays(List<EdgeNodeAvailabilitySample> samples,
                                                            OffsetDateTime since,
                                                            OffsetDateTime now) {
        Map<LocalDate, CalendarCounter> counterMap = new LinkedHashMap<>();
        LocalDate currentDate = since.toLocalDate();
        LocalDate endDate = now.toLocalDate();

        while (!currentDate.isAfter(endDate)) {
            counterMap.put(currentDate, new CalendarCounter());
            currentDate = currentDate.plusDays(1);
        }

        for (EdgeNodeAvailabilitySample sample : samples) {
            LocalDate sampleDate = sample.sampledAt.toLocalDate();
            CalendarCounter counter = counterMap.get(sampleDate);
            if (counter == null) {
                continue;
            }
            counter.totalSamples = counter.totalSamples + 1L;
            if (sample.online) {
                counter.onlineSamples = counter.onlineSamples + 1L;
            }
        }

        List<AvailabilityCalendarDay> days = new ArrayList<>();
        for (Map.Entry<LocalDate, CalendarCounter> entry : counterMap.entrySet()) {
            CalendarCounter counter = entry.getValue();
            Double onlineRate = null;
            if (counter.totalSamples > 0) {
                onlineRate = Double.valueOf(counter.onlineSamples * 100.0D / counter.totalSamples);
            }
            days.add(new AvailabilityCalendarDay(
                    entry.getKey().toString(),
                    onlineRate,
                    counter.totalSamples,
                    counter.onlineSamples
            ));
        }
        return days;
    }

    public record AvailabilityRates(
            AvailabilityRate lastHour,
            AvailabilityRate last24Hours,
            AvailabilityRate last7Days,
            AvailabilityRate last30Days
    ) {
    }

    public record AvailabilityRate(
            Double onlineRate,
            long totalSamples,
            long onlineSamples
    ) {
    }

    public record AvailabilityHistory(
            String nodeId,
            OffsetDateTime from,
            OffsetDateTime to,
            AvailabilityRates rates,
            List<AvailabilitySamplePoint> samples,
            List<AvailabilityOnlinePeriod> onlinePeriods,
            List<AvailabilityCalendarDay> calendarDays
    ) {
    }

    public record AvailabilitySamplePoint(
            OffsetDateTime sampledAt,
            boolean online,
            Long latencyMs
    ) {
    }

    public record AvailabilityOnlinePeriod(
            OffsetDateTime startAt,
            OffsetDateTime endAt
    ) {
    }

    public record AvailabilityCalendarDay(
            String date,
            Double onlineRate,
            long totalSamples,
            long onlineSamples
    ) {
    }

    private static class CalendarCounter {
        long totalSamples;
        long onlineSamples;
    }
}
