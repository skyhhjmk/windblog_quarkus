package com.biliwind.blog.service.edge;

import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.model.EdgeNodeAvailabilitySample;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeNodeAvailabilityServiceTest {

    private final EdgeNodeAvailabilityService availabilityService = new EdgeNodeAvailabilityService();

    @Test
    void shouldTreatFreshWespSessionAsOnline() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EdgeNode node = new EdgeNode();
        node.nodeId = "wesp-test";
        node.isEnabled = true;
        node.status = "ONLINE";
        node.connectionType = EdgeConnectionType.WESP;
        node.lastHeartbeat = now.minusSeconds(30);

        assertTrue(availabilityService.isCurrentlyOnline(node, now));

        node.lastHeartbeat = now.minusSeconds(91);
        assertFalse(availabilityService.isCurrentlyOnline(node, now));
    }

    @Test
    void shouldCalculateRatesFromTheSameSampleWindow() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EdgeNodeAvailabilitySample online = sample(now.minusMinutes(5), true);
        EdgeNodeAvailabilitySample offline = sample(now.minusMinutes(10), false);
        EdgeNodeAvailabilitySample olderOnline = sample(now.minusHours(2), true);

        EdgeNodeAvailabilityService.AvailabilityRates rates = availabilityService.calculateRates(
                "rate-test", List.of(offline, online, olderOnline));

        assertEquals(50.0, rates.lastHour().onlineRate());
        assertEquals(1L, rates.lastHour().onlineSamples());
        assertEquals(3L, rates.last24Hours().totalSamples());
        assertEquals(2L, rates.last24Hours().onlineSamples());
    }

    private EdgeNodeAvailabilitySample sample(OffsetDateTime sampledAt, boolean online) {
        EdgeNodeAvailabilitySample sample = new EdgeNodeAvailabilitySample();
        sample.sampledAt = sampledAt;
        sample.online = online;
        sample.latencyMs = online ? 25L : null;
        return sample;
    }
}
