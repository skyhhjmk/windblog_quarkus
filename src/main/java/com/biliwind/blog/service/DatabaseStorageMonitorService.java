package com.biliwind.blog.service;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Collects PostgreSQL table-level disk storage usage and publishes bounded Micrometer gauges. */
@ApplicationScoped
public class DatabaseStorageMonitorService {

    private static final String DATABASE_SIZE_SQL = """
            SELECT current_database() AS database_name,
                   pg_database_size(current_database()) AS database_total_bytes
            """;

    private static final String TABLE_STORAGE_SQL = """
            SELECT n.nspname AS schema_name,
                   c.relname AS table_name,
                   pg_total_relation_size(c.oid) AS total_bytes,
                   pg_relation_size(c.oid) AS data_bytes,
                   pg_indexes_size(c.oid) AS index_bytes,
                   COALESCE(pg_total_relation_size(c.reltoastrelid), 0) AS toast_bytes,
                   GREATEST(COALESCE(s.n_live_tup, c.reltuples::bigint, 0), 0) AS estimated_rows
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
            WHERE c.relkind IN ('r', 'm')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
            ORDER BY total_bytes DESC, n.nspname ASC, c.relname ASC
            """;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "windblog.db.metrics.max-tables", defaultValue = "100")
    int maxMetricTables = 100;

    private final ConcurrentMap<String, AtomicLong> databaseSizeGauges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TableMetricHolder> tableMetricHolders = new ConcurrentHashMap<>();

    public DatabaseStorageSnapshot collectSnapshot() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseStorageSnapshot snapshot = readSnapshot(connection);
            publishMetrics(snapshot);
            return snapshot;
        }
    }

    public DatabaseStorageSnapshot filterSnapshot(
            DatabaseStorageSnapshot snapshot,
            String schema,
            String search,
            int limit) {
        if (snapshot == null) {
            return new DatabaseStorageSnapshot("", 0L, 0L, 0L, 0L, 0L, 0, Instant.now(), List.of());
        }
        String normalizedSchema = schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT);
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        int boundedLimit = limit <= 0 ? 200 : Math.min(limit, 500);

        List<TableStorageUsage> filtered = new ArrayList<>();
        for (TableStorageUsage item : snapshot.tables()) {
            if (!normalizedSchema.isEmpty()
                    && !item.schema().toLowerCase(Locale.ROOT).equals(normalizedSchema)) {
                continue;
            }
            if (!normalizedSearch.isEmpty()
                    && !item.tableName().toLowerCase(Locale.ROOT).contains(normalizedSearch)
                    && !item.schema().toLowerCase(Locale.ROOT).contains(normalizedSearch)) {
                continue;
            }
            filtered.add(item);
            if (filtered.size() >= boundedLimit) {
                break;
            }
        }

        return new DatabaseStorageSnapshot(
                snapshot.databaseName(),
                snapshot.databaseTotalBytes(),
                snapshot.tableTotalBytes(),
                snapshot.tableDataBytes(),
                snapshot.tableIndexBytes(),
                snapshot.tableToastBytes(),
                snapshot.tableCount(),
                snapshot.collectedAt(),
                List.copyOf(filtered));
    }

    DatabaseStorageSnapshot readSnapshot(Connection connection) throws SQLException {
        String databaseName = "";
        long databaseTotalBytes = 0L;
        try (PreparedStatement stmt = connection.prepareStatement(DATABASE_SIZE_SQL);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                databaseName = rs.getString("database_name");
                databaseTotalBytes = rs.getLong("database_total_bytes");
            }
        }

        List<RawTableRow> rawRows = new ArrayList<>();
        long tableTotalBytes = 0L;
        long tableDataBytes = 0L;
        long tableIndexBytes = 0L;
        long tableToastBytes = 0L;

        try (PreparedStatement stmt = connection.prepareStatement(TABLE_STORAGE_SQL);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                long totalBytes = Math.max(0L, rs.getLong("total_bytes"));
                long dataBytes = Math.max(0L, rs.getLong("data_bytes"));
                long indexBytes = Math.max(0L, rs.getLong("index_bytes"));
                long toastBytes = Math.max(0L, rs.getLong("toast_bytes"));
                long estimatedRows = Math.max(0L, rs.getLong("estimated_rows"));

                tableTotalBytes += totalBytes;
                tableDataBytes += dataBytes;
                tableIndexBytes += indexBytes;
                tableToastBytes += toastBytes;

                rawRows.add(new RawTableRow(schema, tableName, totalBytes, dataBytes, indexBytes, toastBytes, estimatedRows));
            }
        }

        List<TableStorageUsage> tables = new ArrayList<>(rawRows.size());
        for (RawTableRow row : rawRows) {
            double sharePercent = tableTotalBytes > 0
                    ? Math.round((row.totalBytes() * 10000.0) / tableTotalBytes) / 100.0
                    : 0.0;
            tables.add(new TableStorageUsage(
                    row.schema(),
                    row.tableName(),
                    row.totalBytes(),
                    row.dataBytes(),
                    row.indexBytes(),
                    row.toastBytes(),
                    row.estimatedRows(),
                    sharePercent));
        }

        return new DatabaseStorageSnapshot(
                databaseName == null ? "" : databaseName,
                databaseTotalBytes,
                tableTotalBytes,
                tableDataBytes,
                tableIndexBytes,
                tableToastBytes,
                tables.size(),
                Instant.now(),
                List.copyOf(tables));
    }

    void publishMetrics(DatabaseStorageSnapshot snapshot) {
        if (meterRegistry == null || snapshot == null) {
            return;
        }
        String dbName = snapshot.databaseName().isBlank() ? "default" : snapshot.databaseName();
        databaseSizeGauges.computeIfAbsent(dbName, key -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder("windblog.db.database.total_bytes", holder, AtomicLong::doubleValue)
                    .description("Total PostgreSQL database disk usage in bytes")
                    .tag("database", key)
                    .register(meterRegistry);
            return holder;
        }).set(snapshot.databaseTotalBytes());

        int limit = Math.max(1, maxMetricTables);
        Set<String> activeKeys = new HashSet<>();
        int count = 0;
        for (TableStorageUsage table : snapshot.tables()) {
            if (count >= limit) {
                break;
            }
            String key = table.schema() + "." + table.tableName();
            activeKeys.add(key);
            TableMetricHolder holder = tableMetricHolders.computeIfAbsent(key, ignored -> {
                TableMetricHolder created = new TableMetricHolder();
                registerTableGauge("windblog.db.table.total_bytes",
                        "Total disk usage of a PostgreSQL table including indexes and TOAST in bytes",
                        table.schema(), table.tableName(), created.totalBytes);
                registerTableGauge("windblog.db.table.data_bytes",
                        "Main relation heap disk usage of a PostgreSQL table in bytes",
                        table.schema(), table.tableName(), created.dataBytes);
                registerTableGauge("windblog.db.table.index_bytes",
                        "Total index disk usage of a PostgreSQL table in bytes",
                        table.schema(), table.tableName(), created.indexBytes);
                registerTableGauge("windblog.db.table.toast_bytes",
                        "Total TOAST disk usage of a PostgreSQL table in bytes",
                        table.schema(), table.tableName(), created.toastBytes);
                registerTableGauge("windblog.db.table.estimated_rows",
                        "Estimated live row count of a PostgreSQL table",
                        table.schema(), table.tableName(), created.estimatedRows);
                return created;
            });
            holder.update(table);
            count++;
        }

        for (Map.Entry<String, TableMetricHolder> entry : tableMetricHolders.entrySet()) {
            if (!activeKeys.contains(entry.getKey())) {
                entry.getValue().reset();
            }
        }
    }

    private void registerTableGauge(
            String metricName,
            String description,
            String schema,
            String table,
            AtomicLong target) {
        Gauge.builder(metricName, target, AtomicLong::doubleValue)
                .description(description)
                .tag("schema", schema)
                .tag("table", table)
                .register(meterRegistry);
    }

    private record RawTableRow(
            String schema,
            String tableName,
            long totalBytes,
            long dataBytes,
            long indexBytes,
            long toastBytes,
            long estimatedRows) {
    }

    public record TableStorageUsage(
            String schema,
            String tableName,
            long totalBytes,
            long dataBytes,
            long indexBytes,
            long toastBytes,
            long estimatedRows,
            double sharePercent) {
    }

    public record DatabaseStorageSnapshot(
            String databaseName,
            long databaseTotalBytes,
            long tableTotalBytes,
            long tableDataBytes,
            long tableIndexBytes,
            long tableToastBytes,
            int tableCount,
            Instant collectedAt,
            List<TableStorageUsage> tables) {
    }

    private static final class TableMetricHolder {
        private final AtomicLong totalBytes = new AtomicLong();
        private final AtomicLong dataBytes = new AtomicLong();
        private final AtomicLong indexBytes = new AtomicLong();
        private final AtomicLong toastBytes = new AtomicLong();
        private final AtomicLong estimatedRows = new AtomicLong();

        private void update(TableStorageUsage table) {
            totalBytes.set(table.totalBytes());
            dataBytes.set(table.dataBytes());
            indexBytes.set(table.indexBytes());
            toastBytes.set(table.toastBytes());
            estimatedRows.set(table.estimatedRows());
        }

        private void reset() {
            totalBytes.set(0L);
            dataBytes.set(0L);
            indexBytes.set(0L);
            toastBytes.set(0L);
            estimatedRows.set(0L);
        }
    }
}
