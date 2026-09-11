package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "edge_node_availability_samples")
public class EdgeNodeAvailabilitySample extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "node_id", nullable = false, length = 100)
    public String nodeId;

    @Column(name = "sampled_at", nullable = false)
    public OffsetDateTime sampledAt;

    @Column(name = "is_online", nullable = false)
    public boolean online;

    /**
     * At the time of sampling, how old the node heartbeat/session was.
     * This is a freshness delay, rather than a synthetic network RTT.
     */
    @Column(name = "latency_ms")
    public Long latencyMs;
}
