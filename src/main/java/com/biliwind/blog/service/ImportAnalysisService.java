package com.biliwind.blog.service;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.AnalysisResponse;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.AnalyzeRequest;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

@ApplicationScoped
public class ImportAnalysisService {

    private static final long MAX_SQL_FILE_BYTES = 256L * 1024L * 1024L;
    private static final long TTL_SECONDS = 60L * 60L;
    private static final List<String> TARGET_TABLES = List.of(
            "categories", "tags", "posts", "post_category", "post_tag", "post_author",
            "links", "comments", "media", "wa_users");
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?is)\\b(?:insert\\s+into|copy)\\s+(?:only\\s+)?(?:[\\\"`\\w]+\\.)?[\\\"`]?([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern INSERT_COLUMNS_PATTERN = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+(?:[\\\"`\\w]+\\.)?[\\\"`]?([a-zA-Z_][a-zA-Z0-9_]*)[\\\"`]?\\s*\\((.*?)\\)\\s*values");
    private static final Pattern INSERT_VALUES_PATTERN = Pattern.compile(
            "(?is)^insert\\s+into\\s+.+?\\s+values\\s+.+$");
    /** Capture URL tokens without treating surrounding Markdown or newlines as URLs. */
    private static final Pattern URL_TOKEN_PATTERN = Pattern.compile(
            "(?ix)(?:!\\[[^\\]]*\\]|\\[[^\\]]*\\])\\(\\s*['\"]?([^\\s)\"']+)"
                    + "|(?:src|href)\\s*=\\s*['\"]([^'\"]+)"
                    + "|(?<![\\w@])((?:(?:https?:)?//)[A-Za-z0-9._~:/\\#?@!$&'()*+,;=%-]+)"
                    + "|(?<![\\w@])((?:data|mailto|javascript|tel|ftp|file|ws|wss|blob):"
                    + "[A-Za-z0-9._~:/\\#?@!$&'()*+,;=%-]+)");
    private static final Pattern NULL_TOKEN_PATTERN = Pattern.compile("(?i)\\bnull\\b");

    private static final List<EntityPlan> ENTITY_PLANS = List.of(
            new EntityPlan("categories", "categories", "slug", "slug", "分类", "按 slug 合并，已存在记录跳过"),
            new EntityPlan("tags", "tags", "slug", "slug", "标签", "按 slug 合并，已存在记录跳过"),
            new EntityPlan("posts", "posts", "slug", "slug", "文章", "按 slug 合并，已存在文章跳过"),
            new EntityPlan("links", "links", "url", "url", "友情链接", "按 URL 合并，已存在链接跳过"),
            new EntityPlan("media", "media", "file_path", "file_path", "媒体", "下载到主存储并按存储策略同步，不覆盖现有媒体"),
            new EntityPlan("comments", "comments", "post_id/content", "", "评论", "仅导入关联文章存在的评论"),
            new EntityPlan("post_category", "post_category", "post_id/category_id", "", "文章分类关系", "仅导入两端实体均可映射的关系"),
            new EntityPlan("post_tag", "post_tag", "post_id/tag_id", "", "文章标签关系", "仅导入两端实体均可映射的关系"),
            new EntityPlan("post_author", "post_author", "post_id/author_id", "", "文章作者关系", "仅导入可映射用户的关系"),
            new EntityPlan("wa_users", "wa_users", "username/email", "username", "作者用户", "按用户名或邮箱建立现有用户映射，不自动提升权限"));

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"), "windblog-import");

    @Inject
    DataSource targetDataSource;

    public AnalysisResponse analyzeDatabase(AnalyzeRequest request) {
        validateDatabaseRequest(request);
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        warnings.add("执行阶段会重新读取源数据库，请在分析后保持源库结构不变");
        warnings.add("已有记录默认按 slug 或 URL 合并并跳过，媒体资源写入主存储后按策略同步");
        report.put("sourceType", "DATABASE");
        report.put("dialect", "PostgreSQL/JDBC");
        Map<String, Object> tables = inspectDatabase(request);
        report.put("tables", tables);
        report.put("supportedEntities", TARGET_TABLES);
        report.put("urlPolicy", urlPolicy(request.assetPrefix()));
        report.put("mergePlan", analyzeDatabaseMergePlan(request, tables, warnings));
        report.put("dirtyData", analyzeDatabaseDirtyData(request, tables, warnings));
        Map<String, Object> urls = analyzeDatabaseUrls(request, tables, warnings);
        report.put("urls", urls);
        report.put("relativeUrls", urls);
        EmbeddedImageAccumulator embedded = analyzeDatabaseEmbeddedImages(request, tables, warnings);
        report.put("embeddedImages", embedded.toMap());
        report.put("warnings", warnings);
        report.put("blockers", embedded.blockers());
        return createSession("DATABASE", null, report);
    }

    public AnalysisResponse analyzeSqlFile(FileUpload upload) {
        if (upload == null || upload.filePath() == null) {
            throw new IllegalArgumentException("请选择 SQL 文件");
        }
        String fileName = upload.fileName() == null ? "import.sql" : upload.fileName();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            throw new IllegalArgumentException("仅支持 .sql 文件");
        }
        try {
            long size = Files.size(upload.filePath());
            if (size <= 0) {
                throw new IllegalArgumentException("SQL 文件为空");
            }
            if (size > MAX_SQL_FILE_BYTES) {
                throw new IllegalArgumentException("SQL 文件超过 256 MB 限制");
            }
            Files.createDirectories(tempRoot);
            Path target = tempRoot.resolve(UUID.randomUUID() + ".sql");
            Files.copy(upload.filePath(), target, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> report = analyzeSql(target, size, fileName);
            report.put("sha256", sha256(target));
            report.put("supportedEntities", TARGET_TABLES);
            report.put("urlPolicy", urlPolicy(null));
            return createSession("SQL_FILE", target, report);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取 SQL 文件");
        }
    }

    public Session requireSession(String analysisId) {
        if (analysisId == null || analysisId.isBlank()) {
            throw new IllegalArgumentException("缺少分析报告 ID");
        }
        Session session = sessions.get(analysisId);
        if (session == null || session.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            if (session != null) {
                deleteSession(session);
            }
            throw new IllegalArgumentException("分析报告不存在或已过期，请重新分析");
        }
        return session;
    }

    @Scheduled(every = "10m", identity = "windblog-import-analysis-cleanup")
    void cleanupExpired() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sessions.values().removeIf(session -> {
            if (session.expiresAt().isAfter(now)) {
                return false;
            }
            deleteSession(session);
            return true;
        });
    }

    private AnalysisResponse createSession(String sourceType, Path artifact, Map<String, Object> report) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(TTL_SECONDS);
        Session session = new Session(id, sourceType, artifact, report, expiresAt);
        sessions.put(id, session);
        Object blockers = report.get("blockers");
        String status = blockers instanceof java.util.Collection<?> collection && !collection.isEmpty()
                ? "BLOCKED" : "READY";
        return new AnalysisResponse(id, sourceType, status, report, expiresAt.toString());
    }

    private Map<String, Object> inspectDatabase(AnalyzeRequest request) {
        Map<String, Object> tables = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                request.url(), request.username(), request.password())) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : TARGET_TABLES) {
                List<String> columns = new ArrayList<>();
                try (ResultSet result = metadata.getColumns(null, null, table, null)) {
                    while (result.next()) {
                        columns.add(result.getString("COLUMN_NAME"));
                    }
                }
                if (columns.isEmpty()) {
                    continue;
                }
                long count = -1;
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                    if (result.next()) {
                        count = result.getLong(1);
                    }
                } catch (Exception ignored) {
                    // A metadata report should still be useful when a legacy table has an unusual permission.
                }
                tables.put(table, Map.of("columns", columns, "rows", count));
            }
            return tables;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法分析源数据库：" + sanitize(exception.getMessage()));
        }
    }

    private List<Map<String, Object>> analyzeDatabaseMergePlan(AnalyzeRequest request,
            Map<String, Object> tables, List<String> warnings) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EntityPlan plan : ENTITY_PLANS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entity", plan.entity());
            item.put("label", plan.label());
            item.put("sourceTable", plan.sourceTable());
            item.put("key", plan.key());
            item.put("action", plan.action());
            Map<?, ?> table = asMap(tables.get(plan.sourceTable()));
            long sourceRows = table == null ? 0 : numberValue(table.get("rows"));
            item.put("sourceRows", sourceRows);

            if (sourceRows == 0 || plan.key().contains("/")) {
                item.put("mergeCandidates", 0);
                item.put("newRecords", sourceRows);
                item.put("skipExisting", 0);
                item.put("comparison", plan.key().contains("/")
                        ? "关系将在导入阶段按映射结果判断"
                        : "源表不存在或为空");
                result.add(item);
                continue;
            }
            try {
                KeyScan source = readKeyScan(request.url(), request.username(), request.password(),
                        plan.sourceTable(), plan.queryColumn());
                String targetTable = "wa_users".equals(plan.entity()) ? "users" : plan.entity();
                Set<String> targetKeys = "media".equals(plan.entity())
                        ? Set.of() : readTargetKeys(targetTable, plan.queryColumn(), warnings);
                int merge = 0;
                if (!targetKeys.isEmpty()) {
                    for (String key : source.values()) {
                        if (targetKeys.contains(key)) merge++;
                    }
                }
                item.put("mergeCandidates", merge);
                item.put("skipExisting", merge);
                item.put("newRecords", Math.max(0, source.values().size() - merge));
                item.put("sourceBlankKeys", source.blankValues());
                item.put("comparison", "media".equals(plan.entity())
                        ? "媒体按来源地址下载，不按原 ID 覆盖"
                        : "已与当前库的现有键值比对");
            } catch (Exception exception) {
                item.put("mergeCandidates", 0);
                item.put("skipExisting", 0);
                item.put("newRecords", sourceRows);
                item.put("comparison", "当前库比对失败，执行时仍会再次查重");
                warnings.add("无法完成 " + plan.label() + " 的当前库合并比对：" + sanitize(exception.getMessage()));
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> analyzeDatabaseDirtyData(AnalyzeRequest request,
            Map<String, Object> tables, List<String> warnings) {
        List<Map<String, Object>> dirty = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                request.url(), request.username(), request.password())) {
            for (EntityPlan plan : ENTITY_PLANS) {
                if (plan.key().contains("/") || "media".equals(plan.entity())) continue;
                Map<?, ?> table = asMap(tables.get(plan.sourceTable()));
                if (table == null) continue;
                KeyScan scan = readKeyScan(connection, plan.sourceTable(), plan.queryColumn());
                if (scan.blankValues() > 0) {
                    dirty.add(dirtyItem(plan.entity(), "空的合并键 " + plan.key(), scan.blankValues(),
                            "这些记录无法稳定合并，导入时会回退到生成 slug 或跳过"));
                }
                if (scan.duplicateValues() > 0) {
                    dirty.add(dirtyItem(plan.entity(), "重复的合并键 " + plan.key(), scan.duplicateValues(),
                            "重复源记录可能被合并或产生重复内容"));
                }
            }
            scanDirtyText(connection, tables, "posts", "content", "文章内容", dirty);
            scanDirtyText(connection, tables, "comments", "content", "评论内容", dirty);
            scanOrphanRelations(connection, tables, dirty);
        } catch (Exception exception) {
            warnings.add("脏数据扫描未完成：" + sanitize(exception.getMessage()));
        }
        return dirty;
    }

    private Map<String, Object> analyzeDatabaseUrls(AnalyzeRequest request,
            Map<String, Object> tables, List<String> warnings) {
        RelativeUrlAccumulator accumulator = new RelativeUrlAccumulator();
        try (Connection connection = DriverManager.getConnection(
                request.url(), request.username(), request.password())) {
            scanKnownColumns(connection, asMap(tables.get("posts")), "posts", "文章",
                    accumulator, List.of("content", "excerpt", "summary", "cover", "cover_url", "thumbnail_url"));
            scanKnownColumns(connection, asMap(tables.get("comments")), "comments", "评论",
                    accumulator, List.of("content", "url", "avatar_url"));
            scanKnownColumns(connection, asMap(tables.get("links")), "links", "友情链接",
                    accumulator, List.of("url", "logo", "logo_url", "description"));
            scanKnownColumns(connection, asMap(tables.get("media")), "media", "媒体",
                    accumulator, List.of("file_path", "url", "thumbnail_url", "preview_url", "source_url"));
        } catch (Exception exception) {
            warnings.add("URL 扫描未完成：" + sanitize(exception.getMessage()));
        }
        return accumulator.toMap();
    }

    private EmbeddedImageAccumulator analyzeDatabaseEmbeddedImages(AnalyzeRequest request,
            Map<String, Object> tables, List<String> warnings) {
        EmbeddedImageAccumulator accumulator = new EmbeddedImageAccumulator();
        try (Connection connection = DriverManager.getConnection(
                request.url(), request.username(), request.password())) {
            scanEmbeddedColumns(connection, asMap(tables.get("posts")), "posts", "文章", accumulator,
                    List.of("content", "excerpt", "summary", "cover", "cover_url", "thumbnail_url"));
            scanEmbeddedColumns(connection, asMap(tables.get("comments")), "comments", "评论", accumulator,
                    List.of("content", "url", "avatar_url"));
            scanEmbeddedColumns(connection, asMap(tables.get("links")), "links", "友情链接", accumulator,
                    List.of("url", "logo", "logo_url", "image", "icon", "description", "content"));
            scanEmbeddedColumns(connection, asMap(tables.get("media")), "media", "媒体", accumulator,
                    List.of("file_path", "url", "thumbnail_url", "preview_url", "source_url"));
        } catch (Exception exception) {
            warnings.add("内嵌图片扫描未完成：" + sanitize(exception.getMessage()));
        }
        return accumulator;
    }

    private void scanEmbeddedColumns(Connection connection, Map<?, ?> tableInfo, String table,
            String source, EmbeddedImageAccumulator accumulator, List<String> candidates) throws SQLException {
        if (tableInfo == null) return;
        for (String column : candidates) {
            if (!hasColumn(tableInfo, column)) continue;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT " + quoteIdentifier(column)
                         + " FROM " + quoteIdentifier(table))) {
                while (result.next()) scanEmbeddedImages(result.getString(1), source, column, accumulator);
            }
        }
    }

    private EmbeddedImageAccumulator scanEmbeddedImages(String content, String source, String kind) {
        EmbeddedImageAccumulator accumulator = new EmbeddedImageAccumulator();
        scanEmbeddedImages(content, source, kind, accumulator);
        return accumulator;
    }

    private void scanEmbeddedImages(String content, String source, String kind,
            EmbeddedImageAccumulator accumulator) {
        if (content == null || content.isBlank()) return;
        Matcher matcher = EmbeddedDataImageService.DATA_IMAGE_PATTERN.matcher(content);
        while (matcher.find()) accumulator.add(matcher.group(), source, kind);
    }

    private void scanKnownColumns(Connection connection, Map<?, ?> tableInfo, String table,
            String source, RelativeUrlAccumulator accumulator, List<String> candidates)
            throws SQLException {
        if (tableInfo == null) return;
        for (String column : candidates) {
            if (hasColumn(tableInfo, column)) {
                scanTextColumn(connection, table, column, source, accumulator);
            }
        }
    }

    private List<Map<String, Object>> analyzeSqlMergePlan(Map<String, MutableTable> tableStats) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EntityPlan plan : ENTITY_PLANS) {
            MutableTable stats = tableStats.get(plan.sourceTable());
            long rows = stats == null ? 0 : stats.rows;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entity", plan.entity());
            item.put("label", plan.label());
            item.put("sourceTable", plan.sourceTable());
            item.put("key", plan.key());
            item.put("sourceRows", rows);
            item.put("mergeCandidates", null);
            item.put("skipExisting", null);
            item.put("newRecords", null);
            item.put("action", plan.action());
            item.put("comparison", "SQL 文件无法脱离执行库判断；执行时按 slug/URL 查重");
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> analyzeSqlDirtyData(String content,
            Map<String, MutableTable> tableStats) {
        List<Map<String, Object>> dirty = new ArrayList<>();
        int nullValues = countMatches(NULL_TOKEN_PATTERN, content);
        if (nullValues > 0) {
            dirty.add(dirtyItem("SQL 文件", "检测到 NULL 值", nullValues,
                    "目标字段是否允许 NULL 将在执行阶段由临时表结构校验"));
        }
        int emptyValues = countMatches(Pattern.compile("''"), content);
        if (emptyValues > 0) {
            dirty.add(dirtyItem("SQL 文件", "检测到空字符串值", emptyValues,
                    "可能导致标题、slug、URL 或用户名为空"));
        }
        if (content.contains("\\N")) {
            dirty.add(dirtyItem("SQL 文件", "检测到 COPY 风格的 \\N 空值标记", 1,
                    "COPY 数据不会被当前执行器导入"));
        }
        if (tableStats.isEmpty()) {
            dirty.add(dirtyItem("SQL 文件", "没有可分析的目标表数据", 1,
                    "请提供 categories/tags/posts 等目标表的 INSERT 数据"));
        }
        return dirty;
    }

    private Map<String, Object> scanRelativeUrls(String content, String source, String kind) {
        RelativeUrlAccumulator accumulator = new RelativeUrlAccumulator();
        scanText(content, source, kind, accumulator);
        return accumulator.toMap();
    }

    private void scanTextColumn(Connection connection, String table, String column, String source,
            RelativeUrlAccumulator accumulator) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + quoteIdentifier(column)
                     + " FROM " + quoteIdentifier(table))) {
            while (result.next()) {
                scanText(result.getString(1), source, column, accumulator);
            }
        }
    }

    private void scanText(String content, String source, String kind,
            RelativeUrlAccumulator accumulator) {
        if (content == null || content.isBlank()) return;
        Matcher matcher = URL_TOKEN_PATTERN.matcher(content);
        boolean found = false;
        while (matcher.find()) {
            for (int group = 1; group <= 4; group++) {
                String url = matcher.group(group);
                if (url != null && !url.isBlank()) {
                    if (isCommentedProtocolRelativeUrl(content, matcher.start(group), url)) {
                        found = true;
                        continue;
                    }
                    accumulator.add(url, source, kind);
                    found = true;
                    break;
                }
            }
        }
        String standalone = cleanUrlToken(content.trim());
        if (!found && !standalone.contains("\n") && !standalone.contains("\r")
                && isStandaloneUrl(standalone)) {
            accumulator.add(standalone, source, kind);
        }
    }

    private boolean isCommentedProtocolRelativeUrl(String content, int start, String url) {
        if (url == null || !url.startsWith("//") || start < 0) return false;
        int lineStart = Math.max(content.lastIndexOf('\n', start - 1), content.lastIndexOf('\r', start - 1)) + 1;
        String prefix = content.substring(lineStart, start).trim();
        // A line whose first token is //, /*, *, or an HTML comment marker is
        // source commentary, not a protocol-relative resource reference.
        return prefix.isEmpty() || prefix.startsWith("//") || prefix.startsWith("*")
                || prefix.startsWith("/*") || prefix.startsWith("<!--");
    }

    private void scanDirtyText(Connection connection, Map<String, Object> tables, String table,
            String column, String label, List<Map<String, Object>> dirty) throws SQLException {
        Map<?, ?> tableInfo = asMap(tables.get(table));
        if (tableInfo == null || !hasColumn(tableInfo, column)) return;
        int blank = 0;
        int malformed = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + quoteIdentifier(column)
                     + " FROM " + quoteIdentifier(table))) {
            while (result.next()) {
                String value = result.getString(1);
                if (value == null || value.isBlank()) blank++;
                if (value != null && (value.indexOf('\u0000') >= 0 || value.indexOf('\ufffd') >= 0)) malformed++;
            }
        }
        if (blank > 0) dirty.add(dirtyItem(table, label + "为空", blank,
                "空内容会在导入时被跳过或生成不完整记录"));
        if (malformed > 0) dirty.add(dirtyItem(table, label + "包含非法字符", malformed,
                "检测到 NUL 或替换字符，建议先清洗源数据"));
    }

    private void scanOrphanRelations(Connection connection, Map<String, Object> tables,
            List<Map<String, Object>> dirty) throws SQLException {
        checkOrphans(connection, tables, "post_category", "post_id", "posts", "文章分类关系", dirty);
        checkOrphans(connection, tables, "post_category", "category_id", "categories", "文章分类关系", dirty);
        checkOrphans(connection, tables, "post_tag", "post_id", "posts", "文章标签关系", dirty);
        checkOrphans(connection, tables, "post_tag", "tag_id", "tags", "文章标签关系", dirty);
    }

    private void checkOrphans(Connection connection, Map<String, Object> tables, String relationTable,
            String relationColumn, String targetTable, String label, List<Map<String, Object>> dirty)
            throws SQLException {
        if (asMap(tables.get(relationTable)) == null || asMap(tables.get(targetTable)) == null) return;
        Set<Long> ids = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id FROM " + quoteIdentifier(targetTable))) {
            while (result.next()) ids.add(result.getLong(1));
        }
        int orphan = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + quoteIdentifier(relationColumn)
                     + " FROM " + quoteIdentifier(relationTable))) {
            while (result.next()) {
                long id = result.getLong(1);
                if (result.wasNull() || !ids.contains(id)) orphan++;
            }
        }
        if (orphan > 0) dirty.add(dirtyItem(relationTable, label + "存在孤儿引用", orphan,
                "导入阶段会跳过无法映射的关系"));
    }

    private Set<String> readTargetKeys(String table, String column, List<String> warnings) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = targetDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + quoteIdentifier(column)
                     + " FROM " + quoteIdentifier(table))) {
            while (result.next()) {
                String value = normalizeKey(result.getString(1));
                if (!value.isBlank()) values.add(value);
            }
        } catch (SQLException exception) {
            warnings.add("当前库暂时无法读取 " + table + " 的现有键值");
            throw exception;
        }
        return values;
    }

    private KeyScan readKeyScan(String url, String username, String password,
            String table, String column) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            return readKeyScan(connection, table, column);
        }
    }

    private KeyScan readKeyScan(Connection connection, String table, String column) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int blank = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + quoteIdentifier(column)
                     + " FROM " + quoteIdentifier(table))) {
            while (result.next()) {
                String value = normalizeKey(result.getString(1));
                if (value.isBlank()) {
                    blank++;
                } else {
                    counts.merge(value, 1, Integer::sum);
                }
            }
        }
        int duplicates = 0;
        for (int count : counts.values()) if (count > 1) duplicates += count - 1;
        return new KeyScan(counts.keySet(), blank, duplicates);
    }

    private Map<String, Object> dirtyItem(String entity, String issue, int count, String action) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("entity", entity);
        item.put("issue", issue);
        item.put("count", count);
        item.put("action", action);
        return item;
    }

    private int countMatches(Pattern pattern, String content) {
        int count = 0;
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) count++;
        return count;
    }

    private boolean isStandaloneUrl(String value) {
        if (value == null || value.isBlank() || value.contains(" ") || value.contains("\t")) {
            return false;
        }
        String url = value.trim();
        if (url.startsWith("#") || url.startsWith("[") || url.startsWith("<")) return false;
        try {
            URI uri = URI.create(url);
            return uri.getScheme() != null || url.startsWith("/") || url.startsWith(".")
                    || url.startsWith("//") || url.contains("/");
        } catch (IllegalArgumentException exception) {
            return url.startsWith("/") || url.startsWith(".") || url.contains("/");
        }
    }

    private String cleanUrlToken(String value) {
        String result = value == null ? "" : value.trim();
        while (!result.isEmpty() && ".,;!?".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private boolean hasColumn(Map<?, ?> table, String column) {
        Object columns = table.get("columns");
        return columns instanceof List<?> list && list.stream()
                .anyMatch(value -> column.equalsIgnoreCase(String.valueOf(value)));
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private Map<String, Object> analyzeSql(Path file, long size, String fileName) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, MutableTable> tableStats = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        int statements = 0;
        int unsupported = 0;

        for (String statement : splitStatements(content)) {
            String trimmed = stripLeadingComments(statement).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            statements++;
            Matcher tableMatcher = TABLE_PATTERN.matcher(trimmed);
            if (!tableMatcher.find()) {
                String keyword = trimmed.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if (!SetOfSqlKeywords.SAFE_IGNORED.contains(keyword)) {
                    unsupported++;
                }
                continue;
            }
            String table = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!TARGET_TABLES.contains(table)) {
                unsupported++;
                continue;
            }
            MutableTable stats = tableStats.computeIfAbsent(table, ignored -> new MutableTable());
            if (trimmed.regionMatches(true, 0, "INSERT", 0, 6)) {
                if (!INSERT_VALUES_PATTERN.matcher(trimmed).matches()) {
                    blockers.add("表 " + table + " 使用了 INSERT ... SELECT 或其他非 VALUES 语法，当前执行器不会执行");
                    continue;
                }
                stats.rows += countValues(trimmed);
                Matcher columns = INSERT_COLUMNS_PATTERN.matcher(trimmed);
                if (columns.find()) {
                    stats.columns = splitColumns(columns.group(2));
                } else {
                    blockers.add("表 " + table + " 的 INSERT 未列出字段名，无法安全匹配旧版表结构");
                }
            } else {
                if (!trimmed.toLowerCase(Locale.ROOT).contains("from stdin")) {
                    blockers.add("表 " + table + " 使用了不支持的 COPY 来源；只支持 COPY ... FROM stdin");
                    continue;
                }
                String copyHeader = trimmed.lines().findFirst().orElse("");
                if (!copyHeader.matches("(?is)^\\s*COPY\\s+.*\\([^)]*\\)\\s+FROM\\s+STDIN;?$")
                        || copyHeader.matches("(?is).*\\b(csv|binary|delimiter|null|quote|escape|encoding)\\b.*")) {
                    blockers.add("表 " + table + " 的 COPY 只支持带字段列表的默认文本格式 FROM stdin");
                    continue;
                }
                stats.rows += countCopyRows(trimmed);
                stats.copyStatements++;
            }
        }
        if (tableStats.isEmpty()) {
            blockers.add("未发现可识别的目标表 INSERT/COPY 数据");
        }
        if (unsupported > 0) {
            warnings.add("发现 " + unsupported + " 条未识别或非目标 SQL 语句，执行时会跳过");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fileName", fileName);
        report.put("sizeBytes", size);
        report.put("dialect", "PostgreSQL");
        report.put("statementCount", statements);
        report.put("tables", tableStats.entrySet().stream().collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue().toMap()),
                Map::putAll));
        report.put("mergePlan", analyzeSqlMergePlan(tableStats));
        report.put("dirtyData", analyzeSqlDirtyData(content, tableStats));
        Map<String, Object> urls = scanRelativeUrls(content, "SQL 文件", "SQL 内容");
        report.put("urls", urls);
        report.put("relativeUrls", urls);
        EmbeddedImageAccumulator embedded = scanEmbeddedImages(content, "SQL 文件", "SQL 内容");
        report.put("embeddedImages", embedded.toMap());
        report.put("warnings", warnings);
        report.put("blockers", blockers);
        blockers.addAll(embedded.blockers());
        return report;
    }

    public List<String> readStatements(Path file) throws IOException {
        return splitStatements(Files.readString(file, StandardCharsets.UTF_8));
    }

    public String stripLeadingComments(String statement) {
        if (statement == null) return "";
        String result = statement.trim();
        boolean changed;
        do {
            changed = false;
            if (result.startsWith("--")) {
                int newline = result.indexOf('\n');
                result = newline < 0 ? "" : result.substring(newline + 1).trim();
                changed = true;
            } else if (result.startsWith("/*")) {
                int end = result.indexOf("*/", 2);
                result = end < 0 ? "" : result.substring(end + 2).trim();
                changed = true;
            }
        } while (changed && !result.isEmpty());
        return result;
    }

    private List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean copyData = false;
        int lineStart = 0;
        for (int i = 0; i < content.length(); i++) {
            char character = content.charAt(i);
            if (copyData && i == lineStart) {
                int lineEnd = content.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = content.length();
                String line = content.substring(i, lineEnd).replace("\r", "");
                current.append(line);
                if (line.equals("\\.")) {
                    statements.add(current.toString());
                    current.setLength(0);
                    copyData = false;
                } else {
                    current.append('\n');
                }
                i = lineEnd;
                lineStart = lineEnd + 1;
                continue;
            }
            if (character == '\'' && !doubleQuote) {
                if (singleQuote && i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                    current.append(character).append(content.charAt(++i));
                    continue;
                }
                singleQuote = !singleQuote;
            } else if (character == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            }
            if (character == ';' && !singleQuote && !doubleQuote) {
                String sql = current.toString();
                if (sql.trim().matches("(?is)^\\s*COPY\\b.*\\bFROM\\s+STDIN\\s*$")) {
                    copyData = true;
                    current.append(character);
                } else {
                    statements.add(sql);
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
            if (character == '\n') lineStart = i + 1;
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private int countValues(String statement) {
        int count = 0;
        boolean inQuote = false;
        for (int i = 0; i < statement.length(); i++) {
            char character = statement.charAt(i);
            if (character == '\'' && (i == 0 || statement.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (character == '(' && !inQuote) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private int countCopyRows(String statement) {
        int marker = statement.toLowerCase(Locale.ROOT).indexOf("from stdin");
        if (marker < 0) {
            return 0;
        }
        int firstDataLine = statement.indexOf('\n', marker + "from stdin".length());
        if (firstDataLine < 0) return 0;
        String payload = statement.substring(firstDataLine + 1);
        int rows = 0;
        for (String line : payload.split("\\R")) {
            if (!line.isBlank() && !line.trim().equals("\\.")) {
                rows++;
            }
        }
        return rows;
    }

    private List<String> splitColumns(String value) {
        List<String> columns = new ArrayList<>();
        for (String column : value.split(",")) {
            String normalized = column.replace("\"", "").replace("`", "").trim();
            if (!normalized.isEmpty()) {
                columns.add(normalized);
            }
        }
        return columns;
    }

    private void validateDatabaseRequest(AnalyzeRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("请输入源数据库 JDBC URL");
        }
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("请输入源数据库用户名和密码");
        }
    }

    private Map<String, Object> urlPolicy(String assetPrefix) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("sourceBaseUrl", assetPrefix == null ? "" : assetPrefix);
        policy.put("downloadRelativeMedia", true);
        policy.put("preserveExternalLinks", true);
        policy.put("preserveSchemes", List.of("data", "mailto", "javascript", "tel"));
        policy.put("security", "仅接受带主机名的 http(s) 来源前缀；媒体下载经过 SSRF/大小/MIME/病毒扫描策略");
        return policy;
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IOException("无法计算 SQL 文件校验值", exception);
        }
    }

    private String sanitize(String message) {
        return SensitiveMessageSanitizer.sanitize(message);
    }

    private void deleteSession(Session session) {
        if (session.artifact() != null) {
            try {
                Files.deleteIfExists(session.artifact());
            } catch (IOException ignored) {
            }
        }
    }

    public record Session(String id, String sourceType, Path artifact,
                          Map<String, Object> report, OffsetDateTime expiresAt) {
    }

    private record EntityPlan(String entity, String sourceTable, String key, String queryColumn,
                              String label, String action) {
    }

    private record KeyScan(Set<String> values, int blankValues, int duplicateValues) {
    }

    private static final class RelativeUrlAccumulator {
        private int total;
        private int mediaCount;
        private int linkCount;
        private int relativeCount;
        private int absoluteCount;
        private int protocolRelativeCount;
        private int specialCount;
        private int fragmentCount;
        private final Map<String, UrlOccurrence> occurrences = new LinkedHashMap<>();

        void add(String url, String source, String kind) {
            String normalized = cleanToken(url);
            if (normalized.isBlank() || !isValidToken(normalized)) return;
            total++;
            String type = classify(normalized);
            switch (type) {
                case "RELATIVE" -> relativeCount++;
                case "ABSOLUTE" -> absoluteCount++;
                case "PROTOCOL_RELATIVE" -> protocolRelativeCount++;
                case "FRAGMENT" -> fragmentCount++;
                default -> specialCount++;
            }
            boolean media = "file_path".equalsIgnoreCase(kind) || normalized.matches(
                    "(?i).*[./](?:jpg|jpeg|png|gif|webp|svg|mp4|webm|pdf|zip)(?:[?#].*)?$");
            if (media) mediaCount++; else linkCount++;
            UrlOccurrence occurrence = occurrences.get(normalized);
            if (occurrence == null) {
                occurrence = new UrlOccurrence(normalized);
                occurrences.put(normalized, occurrence);
            }
            occurrence.count++;
            if (occurrence.examples.size() < 5) {
                occurrence.examples.add(Map.of("source", source, "kind", kind));
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("mediaCount", mediaCount);
            result.put("linkCount", linkCount);
            result.put("protocolRelativeCount", protocolRelativeCount);
            List<Map<String, Object>> examples = new ArrayList<>();
            for (UrlOccurrence occurrence : occurrences.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("url", occurrence.url);
                item.put("type", occurrence.type);
                item.put("count", occurrence.count);
                item.put("examples", occurrence.examples);
                examples.add(item);
            }
            result.put("relativeCount", relativeCount);
            result.put("absoluteCount", absoluteCount);
            result.put("specialCount", specialCount);
            result.put("fragmentCount", fragmentCount);
            result.put("examples", examples);
            return result;
        }

        private String cleanToken(String value) {
            String result = value == null ? "" : value.trim();
            while (!result.isEmpty() && ".,;!?".indexOf(result.charAt(result.length() - 1)) >= 0) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }

        private String classify(String value) {
            if (value.startsWith("#")) return "FRAGMENT";
            if (value.startsWith("//")) return "PROTOCOL_RELATIVE";
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) return "ABSOLUTE";
            if (lower.matches("^[a-z][a-z0-9+.-]*:.*")) return "SPECIAL";
            return "RELATIVE";
        }

        private boolean isValidToken(String value) {
            // Template/config fragments are not downloadable URLs.
            if (value.indexOf('$') >= 0 || value.indexOf('`') >= 0
                    || value.contains("{{") || value.contains("}}")) {
                return false;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            // Data URLs are analyzed independently in embeddedImages.
            if (lower.startsWith("data:")) return false;
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return hasHttpHost(value.substring(value.indexOf("://") + 3));
            }
            if (value.startsWith("//")) {
                return hasHttpHost(value.substring(2));
            }
            if (lower.matches("^(data|mailto|javascript|tel|ftp|file|ws|wss|blob):.*")) {
                return value.indexOf(':') < value.length() - 1;
            }
            // Unknown `name:value` fragments (for example password:...) are
            // usually configuration/code, not URLs.
            if (lower.matches("^[a-z][a-z0-9+.-]*:.*")) return false;
            if (value.startsWith("#") || value.contains(" ") || value.contains("\t")
                    || value.contains("\r") || value.contains("\n")) return false;
            // A bare prose label (for example “处理CSS的PHP文件”) is not a
            // relative resource. Keep actual paths and filename-like tokens.
            boolean pathLike = value.startsWith("/") || value.startsWith("./")
                    || value.startsWith("../") || value.contains("/")
                    || value.contains("?") || value.contains("#")
                    || value.matches(".*\\.[A-Za-z0-9]{1,12}(?:[?#].*)?$");
            return pathLike;
        }

        private boolean hasHttpHost(String value) {
            int end = value.length();
            for (char separator : new char[]{'/', '?', '#'}) {
                int index = value.indexOf(separator);
                if (index >= 0) end = Math.min(end, index);
            }
            String host = value.substring(0, end);
            if (host.isBlank() || !host.matches("[A-Za-z0-9.-]+(?::[0-9]+)?")) return false;
            String hostWithoutPort = host.replaceFirst(":[0-9]+$", "");
            return hostWithoutPort.equalsIgnoreCase("localhost")
                    || hostWithoutPort.matches(".*\\..*")
                    || hostWithoutPort.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        }
    }

    private static final class EmbeddedImageAccumulator {
        private int totalReferences;
        private int convertibleReferences;
        private long totalBytes;
        private int failed;
        private final Map<String, EmbeddedImageOccurrence> occurrences = new LinkedHashMap<>();
        private final List<String> blockers = new ArrayList<>();
        private final List<Map<String, Object>> failedItems = new ArrayList<>();

        void add(String dataUrl, String source, String kind) {
            totalReferences++;
            try {
                EmbeddedDataImageService.ParsedImage parsed = EmbeddedDataImageService.parse(dataUrl);
                EmbeddedImageOccurrence occurrence = occurrences.computeIfAbsent(parsed.sha256(),
                        ignored -> new EmbeddedImageOccurrence(parsed));
                occurrence.count++;
                convertibleReferences++;
                occurrence.examples.add(Map.of("source", source, "kind", kind));
                if (occurrence.count == 1) totalBytes += parsed.bytes().length;
            } catch (RuntimeException exception) {
                failed++;
                String reason = exception.getMessage() == null ? "格式或内容无效" : exception.getMessage();
                blockers.add("内嵌图片无法导入（" + source + "/" + kind + "）：" + reason);
                failedItems.add(Map.of("mimeType", "未知", "status", "FAILED", "references", 1,
                        "source", source, "kind", kind, "reason", reason,
                        "preview", dataUrl.length() > 96 ? dataUrl.substring(0, 96) + "…" : dataUrl));
            }
        }

        List<String> blockers() { return blockers; }

        Map<String, Object> toMap() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (EmbeddedImageOccurrence occurrence : occurrences.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sha256", occurrence.parsed.sha256());
                item.put("mimeType", occurrence.parsed.mimeType());
                item.put("bytes", occurrence.parsed.bytes().length);
                item.put("references", occurrence.count);
                item.put("status", "CONVERTIBLE");
                item.put("examples", occurrence.examples.subList(0, Math.min(5, occurrence.examples.size())));
                items.add(item);
            }
            List<Map<String, Object>> allItems = new ArrayList<>(items);
            allItems.addAll(failedItems);
            return Map.of("totalReferences", totalReferences, "uniqueImages", occurrences.size(),
                    "totalBytes", totalBytes, "convertible", occurrences.size(), "failed", failed,
                    "replacements", convertibleReferences, "items", allItems);
        }
    }

    private static final class EmbeddedImageOccurrence {
        private final EmbeddedDataImageService.ParsedImage parsed;
        private int count;
        private final List<Map<String, Object>> examples = new ArrayList<>();

        private EmbeddedImageOccurrence(EmbeddedDataImageService.ParsedImage parsed) {
            this.parsed = parsed;
        }
    }

    private static final class UrlOccurrence {
        private final String url;
        private final String type;
        private int count;
        private final List<Map<String, Object>> examples = new ArrayList<>();

        private UrlOccurrence(String url) {
            this.url = url;
            this.type = classify(url);
        }

        private static String classify(String value) {
            if (value.startsWith("#")) return "FRAGMENT";
            if (value.startsWith("//")) return "PROTOCOL_RELATIVE";
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) return "ABSOLUTE";
            if (lower.matches("^[a-z][a-z0-9+.-]*:.*")) return "SPECIAL";
            return "RELATIVE";
        }
    }

    private static final class MutableTable {
        int rows;
        int copyStatements;
        List<String> columns = List.of();

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", rows);
            result.put("copyStatements", copyStatements);
            result.put("columns", columns);
            return result;
        }
    }

    private static final class SetOfSqlKeywords {
        private static final List<String> SAFE_IGNORED = List.of(
                "SET", "SELECT", "COMMENT", "ALTER", "CREATE", "GRANT", "REVOKE", "BEGIN", "COMMIT");
    }
}
