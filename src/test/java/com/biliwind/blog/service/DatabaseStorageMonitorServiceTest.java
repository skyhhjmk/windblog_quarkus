package com.biliwind.blog.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatabaseStorageMonitorServiceTest {

    @Test
    void shouldReadSnapshotAndCalculateSharePercent() throws Exception {
        DatabaseStorageMonitorService service = new DatabaseStorageMonitorService();

        ResultSet dbRs = stubResultSet(List.of(Map.of(
                "database_name", "windblog",
                "database_total_bytes", 1024L * 1024L * 100L)));
        ResultSet tableRs = stubResultSet(List.of(
                Map.of(
                        "schema_name", "public",
                        "table_name", "posts",
                        "total_bytes", 750L,
                        "data_bytes", 500L,
                        "index_bytes", 200L,
                        "toast_bytes", 50L,
                        "estimated_rows", 1200L),
                Map.of(
                        "schema_name", "public",
                        "table_name", "comments",
                        "total_bytes", 250L,
                        "data_bytes", 150L,
                        "index_bytes", 100L,
                        "toast_bytes", 0L,
                        "estimated_rows", 3400L)));

        AtomicInteger queryCall = new AtomicInteger(0);
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        ResultSet rs = queryCall.getAndIncrement() == 0 ? dbRs : tableRs;
                        return stubPreparedStatement(rs);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });

        DatabaseStorageMonitorService.DatabaseStorageSnapshot snapshot = service.readSnapshot(connection);

        assertEquals("windblog", snapshot.databaseName());
        assertEquals(1024L * 1024L * 100L, snapshot.databaseTotalBytes());
        assertEquals(1000L, snapshot.tableTotalBytes());
        assertEquals(650L, snapshot.tableDataBytes());
        assertEquals(300L, snapshot.tableIndexBytes());
        assertEquals(50L, snapshot.tableToastBytes());
        assertEquals(2, snapshot.tableCount());
        assertNotNull(snapshot.collectedAt());
        assertEquals(2, snapshot.tables().size());
        assertEquals("posts", snapshot.tables().get(0).tableName());
        assertEquals(75.0, snapshot.tables().get(0).sharePercent());
        assertEquals(25.0, snapshot.tables().get(1).sharePercent());
    }

    @Test
    void shouldFilterSnapshotBySchemaSearchAndLimit() {
        DatabaseStorageMonitorService service = new DatabaseStorageMonitorService();
        DatabaseStorageMonitorService.DatabaseStorageSnapshot snapshot =
                new DatabaseStorageMonitorService.DatabaseStorageSnapshot(
                        "windblog",
                        5000L,
                        3000L,
                        2000L,
                        800L,
                        200L,
                        3,
                        Instant.now(),
                        List.of(
                                new DatabaseStorageMonitorService.TableStorageUsage(
                                        "public", "posts", 1500L, 1000L, 400L, 100L, 100L, 50.0),
                                new DatabaseStorageMonitorService.TableStorageUsage(
                                        "public", "post_revisions", 1000L, 700L, 200L, 100L, 80L, 33.33),
                                new DatabaseStorageMonitorService.TableStorageUsage(
                                        "audit", "audit_logs", 500L, 300L, 200L, 0L, 50L, 16.67)));

        var filtered = service.filterSnapshot(snapshot, "public", "post", 1);

        assertEquals(3, filtered.tableCount());
        assertEquals(1, filtered.tables().size());
        assertEquals("posts", filtered.tables().get(0).tableName());
    }

    @Test
    void shouldPublishBoundedGaugesAndZeroStaleTables() {
        DatabaseStorageMonitorService service = new DatabaseStorageMonitorService();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        service.meterRegistry = registry;
        service.maxMetricTables = 1;

        var first = new DatabaseStorageMonitorService.DatabaseStorageSnapshot(
                "windblog",
                10000L,
                2000L,
                1400L,
                500L,
                100L,
                2,
                Instant.now(),
                List.of(
                        new DatabaseStorageMonitorService.TableStorageUsage(
                                "public", "posts", 1500L, 1000L, 400L, 100L, 42L, 75.0),
                        new DatabaseStorageMonitorService.TableStorageUsage(
                                "public", "comments", 500L, 400L, 100L, 0L, 10L, 25.0)));

        service.publishMetrics(first);

        assertEquals(10000.0, registry.get("windblog.db.database.total_bytes")
                .tag("database", "windblog").gauge().value());
        assertEquals(1500.0, registry.get("windblog.db.table.total_bytes")
                .tag("schema", "public").tag("table", "posts").gauge().value());
        assertEquals(42.0, registry.get("windblog.db.table.estimated_rows")
                .tag("schema", "public").tag("table", "posts").gauge().value());

        var second = new DatabaseStorageMonitorService.DatabaseStorageSnapshot(
                "windblog",
                12000L,
                2500L,
                1800L,
                600L,
                100L,
                1,
                Instant.now(),
                List.of(
                        new DatabaseStorageMonitorService.TableStorageUsage(
                                "public", "comments", 2500L, 1800L, 600L, 100L, 99L, 100.0)));

        service.publishMetrics(second);

        assertEquals(0.0, registry.get("windblog.db.table.total_bytes")
                .tag("schema", "public").tag("table", "posts").gauge().value());
        assertEquals(2500.0, registry.get("windblog.db.table.total_bytes")
                .tag("schema", "public").tag("table", "comments").gauge().value());
    }

    private static PreparedStatement stubPreparedStatement(ResultSet resultSet) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        return resultSet;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet stubResultSet(List<Map<String, Object>> rows) {
        AtomicInteger index = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return index.incrementAndGet() < rows.size();
                        case "getString":
                            return String.valueOf(rows.get(index.get()).get(args[0]));
                        case "getLong":
                            return ((Number) rows.get(index.get()).get(args[0])).longValue();
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
