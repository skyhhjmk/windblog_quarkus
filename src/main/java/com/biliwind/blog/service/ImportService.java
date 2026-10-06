package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.SlugHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportProgressEvent;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportResult;
import com.biliwind.blog.common.helper.MediaPathHelper;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

@ApplicationScoped
public class ImportService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportService.class);

    private static final int COMMENT_AUDIT_STATUS_APPROVED = 2;

    private static final Pattern MD_IMG_PATTERN = Pattern.compile("(!\\[.*?\\])\\((.*?)\\)");
    private static final Pattern MD_LINK_PATTERN = Pattern.compile("(?<!!)(\\[(.*?)\\])\\((.*?)\\)");
    private static final Pattern HTML_IMG_PATTERN = Pattern.compile("(<img[^>]+src=[\"'])(.*?)([\"'])");
    private static final Pattern HTML_LINK_PATTERN = Pattern.compile("(<a[^>]+href=[\"'])(.*?)([\"'])");
    private static final Pattern URL_DISCOVERY_MD_PATTERN = Pattern.compile("!\\[.*?\\]\\((.*?)\\)");
    private static final Pattern URL_DISCOVERY_HTML_PATTERN = Pattern.compile("src=[\"'](.*?)([\"'])");

    private final BroadcastProcessor<ImportProgressEvent> eventProcessor = BroadcastProcessor.create();
    /**
     * The last durable-ish progress point kept in memory for SSE reconnects.
     * Import execution is still synchronous for now, but a reconnect must not
     * leave the admin page showing an old percentage with no context.
     */
    private volatile ImportProgressEvent latestProgressEvent;
    private final ThreadLocal<String> activeJobId = new ThreadLocal<>();

    @Inject
    MediaManagementService mediaService;

    @Inject
    ImportAnalysisService importAnalysisService;

    @Inject
    Instance<ImportJobService> importJobService;

    @Inject
    EmbeddedDataImageService embeddedDataImageService;

    @Inject
    DataSource targetDataSource;

    @Inject
    PasswordHasher passwordHasher;

    private static final List<String> IMPORT_TABLES = List.of(
            "categories", "tags", "posts", "post_category", "post_tag", "post_author",
            "links", "comments", "media", "wa_users");
    private static final Pattern SQL_TARGET_TABLE = Pattern.compile(
            "(?is)^(?:insert\\s+into|copy|create\\s+(?:temporary\\s+)?table)\\s+"
                    + "(?:if\\s+not\\s+exists\\s+)?(?:[\\\"`\\w]+\\.)?[\\\"`]?([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Map<String, List<String>> LEGACY_STAGE_COLUMNS = Map.ofEntries(
            Map.entry("categories", List.of("id", "name", "slug")),
            Map.entry("tags", List.of("id", "name", "slug")),
            Map.entry("posts", List.of("id", "title", "slug", "content", "published_at", "created_at", "deleted_at")),
            Map.entry("post_category", List.of("post_id", "category_id")),
            Map.entry("post_tag", List.of("post_id", "tag_id")),
            Map.entry("post_author", List.of("post_id", "author_id", "is_primary")),
            Map.entry("links", List.of("id", "name", "url")),
            Map.entry("comments", List.of("id", "post_id", "content", "created_at")),
            Map.entry("media", List.of("id", "file_path")),
            Map.entry("wa_users", List.of("id", "username")));
    private static final Pattern COPY_HEADER = Pattern.compile(
            "(?is)^\\s*COPY\\s+(?:ONLY\\s+)?(?:[\\\"`\\w]+\\.)?[\\\"`]?([a-zA-Z_][a-zA-Z0-9_]*)[\\\"`]?\\s*\\((.*?)\\)\\s+FROM\\s+STDIN(?:\\s+.*)?;?");
    private static final Pattern INSERT_COLUMN_NAMES = Pattern.compile(
            "(?is)^\\s*INSERT\\s+INTO\\s+(?:[\\\"`\\w]+\\.)?[\\\"`]?([a-zA-Z_][a-zA-Z0-9_]*)[\\\"`]?\\s*\\((.*?)\\)\\s*VALUES\\b");

    /**
     * 执行数据导入
     */
    public ImportResult doImport(ImportRequest req, Long operatorId) {
        resetProgressStream();
        emit("info", "准备开始导入数据...", null);
        if (req == null) {
            return new ImportResult(false, "导入请求不能为空", 0, 0, 0, 0, 0);
        }
        if (req.clearExisting()) {
            return new ImportResult(false, "为避免误删现有数据，当前导入不支持清空模式，请使用跳过已有记录策略", 0, 0, 0, 0, 0);
        }
        try (Connection conn = DriverManager.getConnection(req.url(), req.username(), req.password())) {
            return doImportWithConnection(req, operatorId, conn);
        } catch (Exception e) {
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            log.error("导入发生错误: " + safeMessage + " (" + e.getClass().getSimpleName() + ")");
            emit("error", "导入发生错误: " + safeMessage, safeMessage);
            return new ImportResult(false, "导入失败: " + safeMessage, 0, 0, 0, 0, 0);
        }
    }

    public ImportResult runJob(ImportRequest req, Long operatorId, String jobId) {
        activeJobId.set(jobId);
        try { return doImport(req, operatorId); }
        finally { activeJobId.remove(); }
    }

    public ImportResult runSqlJob(java.nio.file.Path sqlFile, ImportRequest req, Long operatorId, String jobId) {
        activeJobId.set(jobId);
        try { return doImportSql(sqlFile, req, operatorId); }
        finally { activeJobId.remove(); }
    }

    public ImportResult doImportSql(java.nio.file.Path sqlFile, ImportRequest req, Long operatorId) {
        resetProgressStream();
        emit("info", "准备从 SQL 文件导入数据...", null);
        if (sqlFile == null || req == null) {
            return new ImportResult(false, "SQL 文件导入请求不能为空", 0, 0, 0, 0, 0);
        }
        if (req.clearExisting()) {
            return new ImportResult(false, "为避免误删现有数据，当前导入不支持清空模式，请使用跳过已有记录策略", 0, 0, 0, 0, 0);
        }
        try (Connection conn = targetDataSource.getConnection()) {
            materializeSqlIntoTemporaryTables(conn, sqlFile);
            return doImportWithConnection(req, operatorId, conn);
        } catch (Exception e) {
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            log.error("SQL 文件导入发生错误: " + safeMessage + " (" + e.getClass().getSimpleName() + ")");
            emit("error", "SQL 文件导入失败: " + safeMessage, safeMessage);
            return new ImportResult(false, "导入失败: " + safeMessage, 0, 0, 0, 0, 0);
        }
    }

    private ImportResult doImportWithConnection(ImportRequest req, Long operatorId, Connection conn)
            throws Exception {
        ImportContext ctx = new ImportContext();
        int categories = 0, tags = 0, posts = 0, links = 0, comments = 0;
        User operator = User.findById(operatorId);
        if (operator == null) {
            operator = User.find("roleName = ?1", RoleConstant.SUPER_ADMIN).firstResult();
        }

        List<String> types = req.types() == null ? List.of() : req.types();
        ctx.tracker().initialize(conn, types);
        emitOverall(ctx, "准备导入", "已选择的导入类型已建立总进度");
        if (types.contains("categories")) {
            emit("info", "正在导入分类...", null);
            categories = importCategories(conn, ctx);
            ctx.tracker().completeEntityType("categories");
            emitOverall(ctx, "分类", "分类处理完成");
            emit("progress", "分类导入完成", Map.of("count", categories));
        }
        if (types.contains("tags")) {
            emit("info", "正在导入标签...", null);
            tags = importTags(conn, ctx);
            ctx.tracker().completeEntityType("tags");
            emitOverall(ctx, "标签", "标签处理完成");
            emit("progress", "标签导入完成", Map.of("count", tags));
        }
        if (types.contains("media") || types.contains("posts")) {
            emit("info", "正在登记外部媒体映射...", null);
            prepareMediaPlaceholders(conn, req.assetPrefix(), operator, ctx,
                    types.contains("media"), types.contains("posts"));
        }
        if (types.contains("media")) {
            ctx.tracker().completeEntityType("media");
            emitOverall(ctx, "媒体", "媒体占位和来源映射已登记");
            emit("progress", "媒体库占位登记完成", null);
        }
        if (types.contains("posts") || types.contains("comments")) {
            emit("info", "正在处理作者/用户映射...", null);
            importUsers(conn, ctx);
        }
        if (types.contains("posts")) {
            emit("info", "正在导入文章并处理附件...", null);
            posts = importPosts(conn, operator, req.assetPrefix(), ctx);
            ctx.tracker().completeEntityType("posts");
            emitOverall(ctx, "文章", "文章处理完成");
            emit("progress", "文章基本数据导入完成", Map.of("count", posts));
            emit("info", "正在重建文章关联关系（分类、标签、作者）...", null);
            importPostRelations(conn, ctx);
            emit("progress", "关联关系重建完成", null);
        }
        if (types.contains("links")) {
            emit("info", "正在导入友情链接...", null);
            links = importLinks(conn, operator, req.assetPrefix(), ctx);
            ctx.tracker().completeEntityType("links");
            emitOverall(ctx, "友情链接", "友情链接处理完成");
            emit("progress", "友情链接导入完成", Map.of("count", links));
        }
        if (types.contains("comments")) {
            emit("info", "正在导入评论...", null);
            comments = importComments(conn, operator, req.assetPrefix(), ctx);
            ctx.tracker().completeEntityType("comments");
            emitOverall(ctx, "评论", "评论处理完成");
            emit("progress", "评论导入完成", Map.of("count", comments));
        }
        if (!ctx.deferredMedia().isEmpty()) {
            emit("info", "文章和关联数据已导入，开始同步外部媒体 ("
                    + ctx.deferredMedia().size() + " 个)...", null);
            processDeferredMedia(operator, ctx);
        }
        emitOverall(ctx, "完成", "全部导入任务完成");
        emit("end", "全部导入任务完成", ctx.tracker().snapshot(), "success");
        return new ImportResult(true, "导入完成", categories, tags, posts, links, comments);
    }

    private void materializeSqlIntoTemporaryTables(Connection conn, java.nio.file.Path sqlFile) throws Exception {
        java.util.Set<String> materializedTables = new HashSet<>();
        try (java.sql.Statement statement = conn.createStatement()) {
            for (String raw : importAnalysisService.readStatements(sqlFile)) {
                String sql = importAnalysisService.stripLeadingComments(raw);
                if (sql.isEmpty()) {
                    continue;
                }
                Matcher matcher = SQL_TARGET_TABLE.matcher(sql);
                if (!matcher.find()) {
                    continue;
                }
                String table = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
                if (!IMPORT_TABLES.contains(table)) {
                    continue;
                }
                if (sql.regionMatches(true, 0, "CREATE", 0, 6)) {
                    // 不执行上传文件中的 DDL。用当前数据库的受控表结构建立临时表，避免 SQL 文件获得任意 DDL 权限。
                    ensureTemporaryTable(statement, table, materializedTables);
                } else if (sql.regionMatches(true, 0, "INSERT", 0, 6)) {
                    ensureTemporaryTable(statement, table, materializedTables);
                    if (!isSimpleInsert(sql, table)) {
                        throw new IllegalArgumentException("表 " + table + " 只支持带列名的 INSERT ... VALUES 导入，不允许执行查询或附加 SQL");
                    }
                    ensureInsertColumns(statement, sql, table);
                    String rewritten = sql.replaceFirst(
                            "(?is)^(insert\\s+into\\s+)(?:[\\\"`\\w]+\\.)?[\\\"`]?" + table + "[\\\"`]?",
                            "$1" + table);
                    statement.execute(rewritten);
                } else if (sql.regionMatches(true, 0, "COPY", 0, 4)) {
                    ensureTemporaryTable(statement, table, materializedTables);
                    importCopyStatement(conn, sql, table);
                }
            }
        }
    }

    private void ensureTemporaryTable(java.sql.Statement statement, String table,
            java.util.Set<String> materializedTables) throws SQLException {
        if (materializedTables.contains(table)) return;
        List<String> columns = LEGACY_STAGE_COLUMNS.get(table);
        if (columns == null) throw new IllegalArgumentException("不支持导入表: " + table);
        String definitions = columns.stream().map(column -> quoteSqlIdentifier(column) + " " + legacyStageType(column))
                .collect(java.util.stream.Collectors.joining(", "));
        statement.execute("CREATE TEMP TABLE " + quoteSqlIdentifier(table) + " (" + definitions + ")");
        materializedTables.add(table);
    }

    private String legacyStageType(String column) {
        if (Set.of("id", "parent_id", "post_id", "category_id", "tag_id", "author_id", "user_id",
                "view_count", "sort_order", "size").contains(column)) return "BIGINT";
        if (Set.of("created_at", "updated_at", "published_at", "deleted_at").contains(column)) return "TIMESTAMP";
        if (Set.of("featured", "allow_comments", "allow_comment", "approved", "spam", "trash", "is_primary")
                .contains(column)) return "BOOLEAN";
        return "TEXT";
    }

    private void importCopyStatement(Connection connection, String raw, String table) throws Exception {
        int headerEnd = raw.indexOf('\n');
        if (headerEnd < 0) throw new IllegalArgumentException("COPY 语句缺少数据行");
        String header = raw.substring(0, headerEnd).trim();
        Matcher matcher = COPY_HEADER.matcher(header);
        if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(table)) {
            throw new IllegalArgumentException("仅支持带明确列名的 COPY 表 (...) FROM stdin 导入");
        }
        if (header.matches("(?is).*\\b(csv|binary|delimiter|null|quote|escape|encoding)\\b.*")) {
            throw new IllegalArgumentException("COPY 仅支持 PostgreSQL 默认文本格式");
        }
        List<String> columns = List.of(matcher.group(2).split(",")).stream()
                .map(column -> column.replace("\"", "").replace("`", "").trim().toLowerCase(java.util.Locale.ROOT))
                .toList();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("COPY 缺少字段");
        }
        try (java.sql.Statement stage = connection.createStatement()) {
            ensureStageColumns(stage, table, columns);
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + quoteSqlIdentifier(table) + " ("
                + columns.stream().map(ImportService::quoteSqlIdentifier).collect(java.util.stream.Collectors.joining(", "))
                + ") VALUES (" + placeholders + ")";
        String payload = raw.substring(headerEnd + 1);
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            int pending = 0;
            for (String line : payload.split("\\R")) {
                if (line.equals("\\.")) break;
                if (line.isEmpty()) continue;
                String[] values = line.split("\\t", -1);
                if (values.length != columns.size()) {
                    throw new IllegalArgumentException("COPY 数据列数与表 " + table + " 的列定义不一致");
                }
                for (int i = 0; i < values.length; i++) {
                    String value = decodeCopyText(values[i]);
                    if (value == null) insert.setNull(i + 1, Types.VARCHAR);
                    else insert.setString(i + 1, value);
                }
                insert.addBatch();
                if (++pending % 500 == 0) insert.executeBatch();
            }
            if (pending % 500 != 0) insert.executeBatch();
        }
    }

    private String decodeCopyText(String value) {
        if (value.equals("\\N")) return null;
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                decoded.append(ch);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'v' -> decoded.append('\u000B');
                case '\\' -> decoded.append('\\');
                case 'x' -> {
                    int hexStart = i + 1;
                    int hexEnd = hexStart;
                    while (hexEnd < value.length() && hexEnd - hexStart < 2
                            && Character.digit(value.charAt(hexEnd), 16) >= 0) hexEnd++;
                    if (hexEnd == hexStart) decoded.append('x');
                    else {
                        decoded.append((char) Integer.parseInt(value.substring(hexStart, hexEnd), 16));
                        i = hexEnd - 1;
                    }
                }
                default -> {
                    if (escaped >= '0' && escaped <= '7') {
                        int octal = escaped - '0';
                        int digits = 1;
                        while (digits < 3 && i + 1 < value.length()
                                && value.charAt(i + 1) >= '0' && value.charAt(i + 1) <= '7') {
                            octal = octal * 8 + (value.charAt(++i) - '0');
                            digits++;
                        }
                        decoded.append((char) octal);
                    } else decoded.append(escaped);
                }
            }
        }
        return decoded.toString();
    }

    private static String quoteSqlIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("SQL 字段名格式无效");
        }
        return "\"" + identifier + "\"";
    }

    private boolean isSimpleInsert(String sql, String table) {
        String normalized = sql.trim();
        String target = "(?is)^insert\\s+into\\s+(?:[\\\"`\\w]+\\.)?[\\\"`]?"
                + Pattern.quote(table) + "[\\\"`]?\\s*\\([^;]*?\\)\\s+values\\s+.+$";
        if (!normalized.matches(target)) return false;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(" returning ") || lower.contains(" on conflict ")) return false;
        int valuesIndex = lower.indexOf("values");
        return valuesIndex >= 0 && containsOnlySqlLiterals(normalized.substring(valuesIndex + "values".length()));
    }

    private boolean containsOnlySqlLiterals(String values) {
        String withoutStrings = values.replaceAll("(?i)\\bE(?=')", " ")
                .replaceAll("'(?:''|\\\\.|[^'])*'", " ");
        return withoutStrings.matches("(?is)[\\s(),]*(?:(?:NULL|TRUE|FALSE)|(?:[+-]?\\d+(?:\\.\\d*)?|[+-]?\\.\\d+)(?:[eE][+-]?\\d+)?|[\\s(),])*");
    }

    private void ensureInsertColumns(java.sql.Statement statement, String sql, String table) throws SQLException {
        Matcher matcher = INSERT_COLUMN_NAMES.matcher(sql);
        if (!matcher.find() || !matcher.group(1).equalsIgnoreCase(table)) {
            throw new IllegalArgumentException("INSERT 缺少有效的字段列表");
        }
        ensureStageColumns(statement, table, splitSqlColumns(matcher.group(2)));
    }

    private List<String> splitSqlColumns(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(column -> column.replace("\"", "").replace("`", "").trim())
                .filter(column -> !column.isEmpty())
                .toList();
    }

    private void ensureStageColumns(java.sql.Statement statement, String table, List<String> columns)
            throws SQLException {
        for (String column : columns) {
            if (!column.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new IllegalArgumentException("导入字段名格式无效");
            }
            statement.execute("ALTER TABLE " + quoteSqlIdentifier(table) + " ADD COLUMN IF NOT EXISTS "
                    + quoteSqlIdentifier(column.toLowerCase(java.util.Locale.ROOT)) + " "
                    + legacyStageType(column.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    static String sanitizeErrorMessage(String message) {
        String sanitized = SensitiveMessageSanitizer.sanitize(message);
        return "未知错误".equals(sanitized) ? "未知导入错误" : sanitized;
    }

    private int importCategories(Connection conn, ImportContext ctx) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM categories ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                long oldId = rs.getLong("id");
                if (isDeleted(rs, cols)) {
                    ctx.tracker().completeEntityItem("categories");
                    emitEntityProgress(ctx, "分类", "跳过已删除分类");
                    continue;
                }
                String categoryName = rs.getString("name");
                String slug = sanitizeImportSlug(rs.getString("slug"), categoryName, "cat-", oldId);

                Category existing = Category.find("slug = ?1", slug).firstResult();
                if (existing != null) {
                    if (columnExists(cols, "status") && rs.getObject("status") != null) {
                        Long existingId = existing.id;
                        boolean enabled = isEnabled(rs, cols);
                        QuarkusTransaction.requiringNew().run(() -> {
                            Category managed = Category.findById(existingId);
                            if (managed != null) managed.enabled = enabled;
                        });
                    }
                    emit("info", "跳过已存在的分类: " + categoryName, slug);
                    ctx.categoryMap().put(oldId, existing.id);
                    ctx.tracker().completeEntityItem("categories");
                    emitEntityProgress(ctx, "分类", "分类处理中");
                    continue;
                }

                final Category c = new Category();
                c.slug = slug;
                c.enabled = isEnabled(rs, cols);
                emit("info", "处理分类: " + categoryName, slug);
                c.name = Map.of("zh-cn", categoryName);
                if (columnExists(cols, "description")) {
                    String desc = rs.getString("description");
                    if (desc != null) c.description = Map.of("zh-cn", desc);
                }

                final Long parentIdToSearch;
                if (columnExists(cols, "parent_id")) {
                    long oldParentId = rs.getLong("parent_id");
                    if (!rs.wasNull() && ctx.categoryMap().containsKey(oldParentId)) {
                        parentIdToSearch = ctx.categoryMap().get(oldParentId);
                    } else {
                        parentIdToSearch = null;
                    }
                } else {
                    parentIdToSearch = null;
                }

                QuarkusTransaction.requiringNew().run(new Runnable() {
                    @Override
                    public void run() {
                        if (parentIdToSearch != null) {
                            c.parent = Category.findById(parentIdToSearch);
                        }
                        c.createdAt = OffsetDateTime.now();
                        c.persist();
                    }
                });

                ctx.categoryMap().put(oldId, c.id);
                ctx.tracker().completeEntityItem("categories");
                emitEntityProgress(ctx, "分类", "分类处理中");
                count++;
            }
        }
        return count;
    }

    public Multi<ImportProgressEvent> getEventStream() {
        ImportProgressEvent latest = latestProgressEvent;
        if (latest == null) {
            return eventProcessor;
        }
        return Multi.createBy().concatenating().streams(
                Multi.createFrom().item(latest), eventProcessor);
    }

    /** Returns the latest progress event for the SSE keep-alive tick. */
    public ImportProgressEvent getProgressHeartbeat() {
        ImportProgressEvent latest = latestProgressEvent;
        return latest == null
                ? new ImportProgressEvent("ping", "keep-alive", null, null)
                : new ImportProgressEvent("heartbeat", latest.message(), latest.data(), latest.status());
    }

    private void resetProgressStream() {
        latestProgressEvent = null;
    }

    private void emit(String type, String message, Object data) {
        publish(new ImportProgressEvent(type, message, data, null));
    }

    private void emit(String type, String message, Object data, String status) {
        publish(new ImportProgressEvent(type, message, data, status));
    }

    private void publish(ImportProgressEvent event) {
        if ("overall".equals(event.type()) || "end".equals(event.type())
                || "error".equals(event.type())) {
            latestProgressEvent = event;
        }
        eventProcessor.onNext(event);
        String jobId = activeJobId.get();
        if (jobId != null) importJobService.get().recordProgress(jobId, event);
    }

    private void emitOverall(ImportContext ctx, String phase, String message) {
        Map<String, Object> data = new HashMap<>(ctx.tracker().snapshot());
        data.put("phase", phase);
        emit("overall", message, data, "processing");
    }

    /**
     * Emits row-level progress at a bounded rate.  Previously only completion
     * of an entire entity type changed the numerator, so a large post or link
     * phase could sit at an apparently arbitrary percentage for a long time.
     */
    private void emitEntityProgress(ImportContext ctx, String phase, String message) {
        if (!ctx.tracker().shouldReportProgress()) {
            return;
        }
        emitOverall(ctx, phase, message);
    }

    private Media downloadImportedMedia(User operator, long placeholderId,
                                        String fullUrl, ImportContext ctx) throws Exception {
        ImportProgressTracker tracker = ctx.tracker();
        tracker.registerResource(fullUrl);
        int resourceIndex = tracker.resourceIndex(fullUrl);
        DownloadState state = new DownloadState();
        emit("download", "开始下载资源 " + sanitizeErrorMessage(fullUrl),
                downloadData(tracker, fullUrl, resourceIndex, state, "started"), "processing");
        try {
            Media media = mediaService.importFromUrlIntoPlaceholder(operator, placeholderId, fullUrl,
                    (downloaded, total, statusCode, redirectCount) -> {
                        state.statusCode = statusCode;
                        state.redirectCount = redirectCount;
                        state.downloadedBytes = downloaded;
                        state.totalBytes = total;
                        if (state.shouldReport(downloaded, total)) {
                            emit("download", "下载资源 " + sanitizeErrorMessage(fullUrl),
                                    downloadData(tracker, fullUrl, resourceIndex, state, "downloading"),
                                    "processing");
                        }
                    });
            tracker.completeResource(fullUrl, true);
            emit("download", "资源下载完成 " + sanitizeErrorMessage(fullUrl),
                    downloadData(tracker, fullUrl, resourceIndex, state, "completed"), "success");
            emitOverall(ctx, "媒体", "资源下载完成");
            return media;
        } catch (Exception exception) {
            tracker.completeResource(fullUrl, false);
            state.error = sanitizeErrorMessage(exception.getMessage());
            emit("download", "资源下载失败 " + sanitizeErrorMessage(fullUrl),
                    downloadData(tracker, fullUrl, resourceIndex, state, "failed"), "failed");
            emitOverall(ctx, "媒体", "资源下载失败");
            throw exception;
        }
    }

    private void processDeferredMedia(User operator, ImportContext ctx) {
        for (DeferredMediaImport deferred : ctx.deferredMedia().values()) {
            String safeUrl = sanitizeErrorMessage(deferred.sourceUrl());
            emit("info", "开始同步外部媒体: " + safeUrl, Map.of("mediaId", deferred.mediaId()));
            try {
                mediaService.markImportPlaceholderProcessing(deferred.mediaId());
                downloadImportedMedia(operator, deferred.mediaId(), deferred.sourceUrl(), ctx);
            } catch (Exception exception) {
                String safeMessage = sanitizeErrorMessage(exception.getMessage());
                mediaService.markImportPlaceholderFailed(deferred.mediaId(), safeMessage);
                emit("error", "外部媒体同步失败，来源映射已保留，可在媒体库重试: " + safeUrl,
                        Map.of("mediaId", deferred.mediaId(), "error", safeMessage));
            }
        }
    }

    private Map<String, Object> downloadData(ImportProgressTracker tracker, String url,
                                               int resourceIndex, DownloadState state, String phase) {
        Map<String, Object> data = new HashMap<>();
        data.put("resourceUrl", sanitizeErrorMessage(url));
        data.put("resourceIndex", resourceIndex);
        data.put("resourceTotal", tracker.resourceTotal());
        data.put("completedResources", tracker.completedResources());
        data.put("successfulResources", tracker.successfulResources());
        data.put("failedResources", tracker.failedResources());
        data.put("downloadedBytes", state.downloadedBytes);
        data.put("totalBytes", state.totalBytes);
        data.put("httpStatus", state.statusCode);
        data.put("redirectCount", state.redirectCount);
        data.put("phase", phase);
        if (state.error != null) data.put("error", state.error);
        return data;
    }

    /**
     * 测试外部数据库连接
     */
    public boolean testConnection(String driver, String url, String username, String password) {
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    private int importTags(Connection conn, ImportContext ctx) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM tags ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                long oldId = rs.getLong("id");
                if (isDeleted(rs, cols)) {
                    ctx.tracker().completeEntityItem("tags");
                    emitEntityProgress(ctx, "标签", "跳过已删除标签");
                    continue;
                }
                String tagName = rs.getString("name");
                String slug = sanitizeImportSlug(rs.getString("slug"), tagName, "tag-", System.currentTimeMillis());

                Tag existing = Tag.find("slug = ?1", slug).firstResult();
                if (existing != null) {
                    emit("info", "跳过已存在的标签: " + tagName, slug);
                    ctx.tagMap().put(oldId, existing.id);
                    ctx.tracker().completeEntityItem("tags");
                    emitEntityProgress(ctx, "标签", "标签处理中");
                    continue;
                }

                emit("info", "处理标签: " + tagName, slug);
                final Tag t = new Tag();
                t.slug = slug;
                t.name = Map.of("zh-cn", tagName);
                if (columnExists(cols, "description")) {
                    String desc = rs.getString("description");
                    if (desc != null) t.description = Map.of("zh-cn", desc);
                }
                t.createdAt = OffsetDateTime.now();

                QuarkusTransaction.requiringNew().run(new Runnable() {
                    @Override
                    public void run() {
                        t.persist();
                    }
                });
                ctx.tagMap().put(oldId, t.id);
                ctx.tracker().completeEntityItem("tags");
                emitEntityProgress(ctx, "标签", "标签处理中");
                count++;
            }
        }
        return count;
    }

    private void prepareMediaPlaceholders(Connection conn, String assetPrefix, User operator,
                                         ImportContext ctx, boolean includeMediaTable,
                                         boolean includePosts) throws SQLException {
        // 发现池，Key 是探测到的各种路径形式，Value 是对应的下载 URL
        Map<String, String> discoveryMap = new HashMap<>();

        // 先登记 legacy media 表中的来源 URL，不在文章和关系导入前发起网络请求。
        if (includeMediaTable) {
            String mediaSql = "SELECT * FROM media";
            try (PreparedStatement ps = conn.prepareStatement(mediaSql);
                 ResultSet rs = ps.executeQuery()) {
                List<String> mediaColumns = getAvailableColumns(rs);
                while (rs.next()) {
                    if (isDeleted(rs, mediaColumns)) {
                        ctx.tracker().completeEntityItem("media");
                        emitEntityProgress(ctx, "媒体", "跳过已删除媒体");
                        continue;
                    }
                    String filePath = rs.getString("file_path");
                    if (filePath == null) {
                        ctx.tracker().completeEntityItem("media");
                        emitEntityProgress(ctx, "媒体", "媒体资源发现中");
                        continue;
                    }

                    if (isEmbeddedImageDataUrl(filePath)) {
                        String imported = importEmbeddedImage(filePath, operator, ctx);
                        ctx.urlMap().put(filePath, imported);
                        ctx.tracker().completeEntityItem("media");
                        emitEntityProgress(ctx, "媒体", "内嵌媒体资源处理中");
                        continue;
                    }

                    addDiscoveredMediaUrl(filePath, assetPrefix, discoveryMap);
                    ctx.tracker().completeEntityItem("media");
                    emitEntityProgress(ctx, "媒体", "媒体来源登记中");
                }
            } catch (SQLException e) {
                String safeMessage = sanitizeErrorMessage(e.getMessage());
                emit("info", "读取 media 表失败，将仅依赖文章内容分析: " + safeMessage, null);
            }
        }

        // 文章要先拿到稳定占位地址，媒体下载延后到文章、评论和友链全部写入之后。
        if (includePosts) extractUrlsFromPosts(conn, assetPrefix, discoveryMap, ctx);

        // 同一来源 URL 只创建一个附件；所有 legacy 路径别名都记录在该附件的 metadata 中。
        Map<String, List<String>> reverseMap = new HashMap<>();
        for (Map.Entry<String, String> entry : discoveryMap.entrySet()) {
            if (!isAbsoluteHttpUrl(entry.getValue())) {
                emit("info", "跳过无法解析的相对媒体地址（请填写 http(s) 来源前缀）: "
                        + sanitizeErrorMessage(entry.getKey()), null);
                continue;
            }
            reverseMap.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        for (Map.Entry<String, List<String>> entry : reverseMap.entrySet()) {
            String fullUrl = entry.getKey();
            List<String> refPaths = entry.getValue();
            registerDeferredMedia(fullUrl, refPaths, operator, ctx);
        }
    }

    private void addDiscoveredMediaUrl(String url, String assetPrefix, Map<String, String> discoveryMap) {
        if (url == null || url.isBlank() || isNonMediaScheme(url) || isEmbeddedImageDataUrl(url)) return;
        boolean absoluteSource = isAbsoluteHttpUrl(url);
        String oldRelUrl = absoluteSource ? url : normalizeOldRelUrl(url);
        String fullUrl = absoluteSource ? url : url.startsWith("//")
                ? resolveRelativeUrl(assetPrefix, url)
                : formatUrl(assetPrefix, oldRelUrl);
        discoveryMap.put(url, fullUrl);
        discoveryMap.put(oldRelUrl, fullUrl);
        if (!url.startsWith("/")) discoveryMap.put("/" + url, fullUrl);
    }

    private void registerDeferredMedia(String fullUrl, List<String> aliases,
                                       User operator, ImportContext ctx) {
        if (fullUrl == null || !isAbsoluteHttpUrl(fullUrl)) return;
        DeferredMediaImport existing = ctx.deferredMedia().get(fullUrl);
        if (existing != null) {
            List<String> sourceMappings = aliases == null ? List.of() : aliases.stream()
                    .filter(alias -> alias != null && !alias.isBlank())
                    .distinct().toList();
            mediaService.addImportPlaceholderMappings(existing.mediaId(), sourceMappings);
            for (String alias : sourceMappings) ctx.urlMap().put(alias, existing.placeholderUrl());
            ctx.urlMap().put(fullUrl, existing.placeholderUrl());
            return;
        }

        List<String> sourceMappings = aliases == null ? List.of() : aliases.stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .distinct().toList();
        Media placeholder = mediaService.createImportPlaceholder(operator, fullUrl, sourceMappings);
        String placeholderUrl = mediaService.importPlaceholderUrl(placeholder);
        DeferredMediaImport deferred = new DeferredMediaImport(fullUrl, placeholder.id, placeholderUrl);
        ctx.deferredMedia().put(fullUrl, deferred);
        ctx.tracker().registerResource(fullUrl);
        for (String alias : sourceMappings) ctx.urlMap().put(alias, placeholderUrl);
        ctx.urlMap().put(fullUrl, placeholderUrl);
        emit("info", "已登记外部媒体映射并创建附件占位: " + sanitizeErrorMessage(fullUrl),
                Map.of("mediaId", placeholder.id, "sourceMappings", sourceMappings.size()));
    }

    private void extractUrlsFromPosts(Connection conn, String assetPrefix,
                                      Map<String, String> discoveryMap, ImportContext ctx) throws SQLException {
        String sql = "SELECT content FROM posts WHERE deleted_at IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String content = rs.getString("content");
                if (content == null || content.isBlank()) continue;

                // 使用正则提取所有可能的 URL
                // Markdown ![]() or []()
                Matcher mdMatcher = URL_DISCOVERY_MD_PATTERN.matcher(content);
                while (mdMatcher.find()) {
                    addDiscoveredUrl(mdMatcher.group(1), assetPrefix, discoveryMap);
                }

                // HTML src="..."
                Matcher htmlMatcher = URL_DISCOVERY_HTML_PATTERN.matcher(content);
                while (htmlMatcher.find()) {
                    addDiscoveredUrl(htmlMatcher.group(1), assetPrefix, discoveryMap);
                }
                emitEntityProgress(ctx, "媒体", "正在分析文章资源");
            }
        }
    }

    private void addDiscoveredUrl(String url, String assetPrefix, Map<String, String> discoveryMap) {
        if (url == null || url.isBlank() || isNonMediaScheme(url) || isEmbeddedImageDataUrl(url)) return;

        boolean absoluteSource = isAbsoluteHttpUrl(url);
        String oldRelUrl = absoluteSource ? url : normalizeOldRelUrl(url);
        String fullUrl = absoluteSource ? url : url.startsWith("//")
                ? resolveRelativeUrl(assetPrefix, url)
                : formatUrl(assetPrefix, oldRelUrl);
        discoveryMap.put(url, fullUrl);
        discoveryMap.put(oldRelUrl, fullUrl);
    }

    private String normalizeOldRelUrl(String path) {
        return MediaPathHelper.normalizeLegacyUploadPath(path);
    }

    private int importPosts(Connection conn, User operator, String assetPrefix, ImportContext ctx) throws SQLException {
        int count = 0;

        // 预加载用户映射中需要的用户，避免循环内逐条查询
        Map<Long, User> usersById = new HashMap<>();
        if (!ctx.userMap().isEmpty()) {
            java.util.Set<Long> neededIds = new java.util.HashSet<>(ctx.userMap().values());
            List<User> neededUsers = User.list("id in ?1", neededIds);
            for (User u : neededUsers) {
                usersById.put(u.id, u);
            }
        }

        String sql = "SELECT * FROM posts ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                if (isDeleted(rs, cols)) {
                    ctx.tracker().completeEntityItem("posts");
                    emitEntityProgress(ctx, "文章", "跳过已删除文章");
                    continue;
                }
                String title = rs.getString("title");
                String slug = sanitizeImportSlug(rs.getString("slug"), title, "post-", rs.getLong("id"));

                if (Post.count("slug = ?1", slug) > 0) {
                    Post existing = Post.find("slug = ?1", slug).firstResult();
                    if (existing != null) ctx.postMap().put(rs.getLong("id"), existing.id);
                    emit("info", "跳过已存在的文章: " + title, slug);
                    ctx.tracker().completeEntityItem("posts");
                    emitEntityProgress(ctx, "文章", "文章处理中");
                    continue;
                }

                emit("info", "处理文章: " + title, slug);
                final Post p = new Post();
                p.slug = slug;
                p.title = Map.of("zh-cn", title);
                String excerptVal = null;
                if (columnExists(cols, "excerpt")) {
                    excerptVal = rs.getString("excerpt");
                } else if (columnExists(cols, "summary")) {
                    excerptVal = rs.getString("summary");
                }
                if (excerptVal != null) {
                    p.summary = Map.of("zh-cn", processContentLinks(excerptVal, assetPrefix, operator, ctx));
                }

                if (columnExists(cols, "ai_summary")) {
                    String aiSummary = rs.getString("ai_summary");
                    if (aiSummary != null) {
                        p.aiSummary = Map.of("zh-cn", processContentLinks(aiSummary, assetPrefix, operator, ctx));
                    }
                }

                p.status = mapStatus(columnExists(cols, "status") ? rs.getString("status") : null);
                p.visibility = mapVisibility(columnExists(cols, "visibility") ? rs.getString("visibility") : null);
                p.password = columnExists(cols, "password") ? rs.getString("password") : null;
                p.featured = columnExists(cols, "featured") && rs.getBoolean("featured");
                p.allowComment = true;
                if (columnExists(cols, "allow_comments")) {
                    p.allowComment = rs.getBoolean("allow_comments");
                } else if (columnExists(cols, "allow_comment")) {
                    p.allowComment = rs.getBoolean("allow_comment");
                }

                if (columnExists(cols, "view_count")) {
                    p.viewCount = rs.getLong("view_count");
                }

                p.renderType = mapRenderType(columnExists(cols, "content_type") ? rs.getString("content_type") : null);

                // 尝试映射作者，如果映射不存在则默认为当前操作者
                User postAuthor = operator;
                if (columnExists(cols, "author_id")) {
                    long oldAuthId = rs.getLong("author_id");
                    if (!rs.wasNull() && ctx.userMap().containsKey(oldAuthId)) {
                        postAuthor = usersById.get(ctx.userMap().get(oldAuthId));
                    }
                } else if (columnExists(cols, "user_id")) {
                    long oldUserId = rs.getLong("user_id");
                    if (!rs.wasNull() && ctx.userMap().containsKey(oldUserId)) {
                        postAuthor = usersById.get(ctx.userMap().get(oldUserId));
                    }
                }
                p.user = postAuthor != null ? postAuthor : operator;
                if (columnExists(cols, "author_id")) {
                    long legacyAuthorId = rs.getLong("author_id");
                    if (!rs.wasNull()) p.authorName = ctx.authorNames().get(legacyAuthorId);
                } else if (columnExists(cols, "user_id")) {
                    long legacyUserId = rs.getLong("user_id");
                    if (!rs.wasNull()) p.authorName = ctx.authorNames().get(legacyUserId);
                }

                Timestamp publishedAtTs = columnExists(cols, "published_at") ? rs.getTimestamp("published_at") : null;
                if (publishedAtTs != null)
                    p.publishedAt = OffsetDateTime.ofInstant(publishedAtTs.toInstant(), ZoneId.systemDefault());

                Timestamp createdAtTs = columnExists(cols, "created_at") ? rs.getTimestamp("created_at") : null;
                if (createdAtTs != null)
                    p.createdAt = OffsetDateTime.ofInstant(createdAtTs.toInstant(), ZoneId.systemDefault());

                p.updatedAt = OffsetDateTime.now();

                final String contentMarkdownRaw = rs.getString("content");
                final String contentMarkdown = processContentLinks(contentMarkdownRaw, assetPrefix, operator, ctx);

                final long oldPostId = rs.getLong("id");
                QuarkusTransaction.requiringNew().run(new Runnable() {
                    @Override
                    public void run() {
                        p.persist();

                        // Create revision
                        PostRevision rev = new PostRevision();
                        rev.post = p;
                        rev.title = p.title;
                        rev.contentMarkdown = Map.of("zh-cn", contentMarkdown);
                        rev.editorType = (short) (p.renderType == PostRenderType.HTML ? 1 : 0);
                        rev.revisionNumber = 1;
                        rev.createdBy = operator;
                        rev.createdAt = p.createdAt != null ? p.createdAt : OffsetDateTime.now();
                        rev.persist();

                        p.currentRevision = rev;
                        if (p.status == PostStatus.PUBLISHED) {
                            p.publishedRevision = rev;
                        }
                        p.persist();
                    }
                });

                mediaService.syncImportPlaceholderReferences(p, Map.of("zh-cn", contentMarkdown));

                ctx.postMap().put(oldPostId, p.id);
                ctx.tracker().completeEntityItem("posts");
                emitEntityProgress(ctx, "文章", "文章处理中");
                count++;
            }
        }
        return count;
    }

    /**
     * 格式化 URL，补全前缀并合并双斜杠（忽略协议部分的 //）
     */
    private String formatUrl(String prefix, String path) {
        if (path == null || path.isBlank()) return path;
        if (isAbsoluteHttpUrl(path)) return path;
        return resolveRelativeUrl(prefix, path);
    }

    private int importLinks(Connection conn, User operator, String assetPrefix, ImportContext ctx) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM links ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                if (isDeleted(rs, cols)) {
                    ctx.tracker().completeEntityItem("links");
                    emitEntityProgress(ctx, "友情链接", "跳过已删除友链");
                    continue;
                }
                String url = rs.getString("url");
                if (Link.count("url = ?1", url) > 0) {
                    ctx.tracker().completeEntityItem("links");
                    emitEntityProgress(ctx, "友情链接", "友情链接处理中");
                    continue;
                }

                final Link l = new Link();
                l.url = url;
                l.name = rs.getString("name");
                if (columnExists(cols, "description")) l.description = processContentLinks(rs.getString("description"), assetPrefix, operator, ctx);
                if (columnExists(cols, "image")) l.image = resolveAndDownload(rs.getString("image"), assetPrefix, operator, ctx);
                if (columnExists(cols, "icon")) l.icon = resolveAndDownload(rs.getString("icon"), assetPrefix, operator, ctx);
                if (columnExists(cols, "sort_order")) l.sortOrder = rs.getInt("sort_order");
                l.status = isEnabled(rs, cols) ? (short) 1 : (short) 2;
                l.applicationStatus = 1; // Imported links are treated as approved.
                l.availabilityStatus = "UNKNOWN";
                l.backlinkStatus = "UNKNOWN";
                l.target = "_blank";
                l.redirectType = 1; // Direct
                l.showUrl = false;
                l.type = LinkType.FRIENDLY_LINK;
                l.createdAt = OffsetDateTime.now();

                QuarkusTransaction.requiringNew().run(new Runnable() {
                    @Override
                    public void run() {
                        l.persist();
                    }
                });
                ctx.tracker().completeEntityItem("links");
                emitEntityProgress(ctx, "友情链接", "友情链接处理中");
                count++;
            }
        }
        return count;
    }

    private void importUsers(Connection conn, ImportContext ctx) throws SQLException {
        // 在 windblog_webman 中，作者表通常是 wa_users
        String sql = "SELECT * FROM wa_users ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                long oldId = rs.getLong("id");
                String username = trimToNull(rs.getString("username"));
                String nickname = trimToNull(columnExists(cols, "nickname") ? rs.getString("nickname") : null);
                String email = normalizeEmail(columnExists(cols, "email") ? rs.getString("email") : null);
                ctx.authorNames().put(oldId, nickname != null ? nickname : username);
                User byUsername = username == null ? null : User.find("username = ?1", username).firstResult();
                User byEmail = email == null ? null : User.find("lower(email) = ?1", email).firstResult();
                if (byUsername != null && byEmail != null && !byUsername.id.equals(byEmail.id)) {
                    emit("warning", "旧用户用户名与邮箱分别匹配到不同账号，保留署名而不绑定账号", Map.of("legacyUserId", oldId));
                    continue;
                }
                User existing = byUsername != null ? byUsername : byEmail;
                if (existing == null && username != null && email != null && !isDeleted(rs, cols)) {
                    User imported = new User();
                    imported.username = username;
                    imported.email = email;
                    imported.nickname = nickname;
                    imported.status = 0;
                    imported.mustResetPassword = true;
                    imported.roleName = RoleConstant.USER;
                    imported.password = passwordHasher.hash(UUID.randomUUID().toString());
                    imported.createdAt = readTimestamp(rs, cols, "created_at");
                    QuarkusTransaction.requiringNew().run(imported::persist);
                    existing = User.find("username = ?1", username).firstResult();
                }
                if (existing != null) ctx.userMap().put(oldId, existing.id);
                emitEntityProgress(ctx, "作者映射", "正在处理作者/用户映射");
            }
        } catch (SQLException e) {
            emit("info", "未找到旧系统的用户表 (wa_users)，将跳过作者映射: "
                    + sanitizeErrorMessage(e.getMessage()), null);
        }
    }

    private void importPostRelations(Connection conn, ImportContext ctx) throws SQLException {
        // 1. 迁移分类关联 (post_category)
        // Each association is committed in its own transaction, so resolve entities
        // inside that transaction and let Hibernate dirty checking persist updates.
        String catSql = "SELECT post_id, category_id FROM post_category ORDER BY post_id, category_id";
        try (PreparedStatement ps = conn.prepareStatement(catSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long oldPostId = rs.getLong("post_id");
                long oldCatId = rs.getLong("category_id");

                Long newPostId = ctx.postMap().get(oldPostId);
                Long newCatId = ctx.categoryMap().get(oldCatId);

                if (newPostId != null && newCatId != null) {
                    QuarkusTransaction.requiringNew().run(() -> {
                        Post p = Post.findById(newPostId);
                        Category c = Category.findById(newCatId);
                        if (p != null && c != null) {
                            if (p.category == null) p.category = c;
                            if (p.categories.stream().noneMatch(existing -> existing.id.equals(c.id))) {
                                p.categories.add(c);
                            }
                            if (p.category != null && p.categories.stream().noneMatch(existing -> existing.id.equals(p.category.id))) {
                                p.categories.add(0, p.category);
                            }
                        }
                    });
                }
                emitEntityProgress(ctx, "文章关联", "正在处理分类关联");
            }
        } catch (SQLException e) {
            emit("info", "处理分类关联时跳过 (可能表不存在): "
                    + sanitizeErrorMessage(e.getMessage()), null);
        }

        // 2. 迁移标签关联 (post_tag)
        String tagSql = "SELECT post_id, tag_id FROM post_tag";
        try (PreparedStatement ps = conn.prepareStatement(tagSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long oldPostId = rs.getLong("post_id");
                long oldTagId = rs.getLong("tag_id");

                Long newPostId = ctx.postMap().get(oldPostId);
                Long newTagId = ctx.tagMap().get(oldTagId);

                if (newPostId != null && newTagId != null) {
                    QuarkusTransaction.requiringNew().run(() -> {
                        Post p = Post.findById(newPostId);
                        Tag t = Tag.findById(newTagId);
                        if (p != null && t != null) {
                            if (PostTag.count("id.postId = ?1 and id.tagId = ?2", p.id, t.id) == 0) {
                                PostTag pt = new PostTag();
                                pt.id = new PostTagId(p.id, t.id);
                                pt.post = p;
                                pt.tag = t;
                                pt.persist();
                            }
                        }
                    });
                }
                emitEntityProgress(ctx, "文章关联", "正在处理标签关联");
            }
        } catch (SQLException e) {
            emit("info", "处理标签关联时跳过 (可能表不存在): "
                    + sanitizeErrorMessage(e.getMessage()), null);
        }

        // 3. 迁移作者关联 (post_author)
        String authSql = "SELECT * FROM post_author ORDER BY post_id, author_id";
        try (PreparedStatement ps = conn.prepareStatement(authSql);
             ResultSet rs = ps.executeQuery()) {
            List<String> authorColumns = getAvailableColumns(rs);
            while (rs.next()) {
                if (columnExists(authorColumns, "is_primary") && rs.getObject("is_primary") != null
                        && !isTruthy(rs.getObject("is_primary"))) continue;
                long oldPostId = rs.getLong("post_id");
                long oldAuthId = rs.getLong("author_id");

                Long newPostId = ctx.postMap().get(oldPostId);
                Long newAuthId = ctx.userMap().get(oldAuthId);

                if (newPostId != null && newAuthId != null) {
                    QuarkusTransaction.requiringNew().run(() -> {
                        Post p = Post.findById(newPostId);
                        User u = User.findById(newAuthId);
                        if (p != null && u != null) {
                            p.user = u;
                        }
                    });
                }
                emitEntityProgress(ctx, "文章关联", "正在处理作者关联");
            }
        } catch (SQLException e) {
            // 可能没有 post_author 表，或者是 wa_posts 里直接带 author_id
        }
    }

    private int importComments(Connection conn, User operator, String assetPrefix, ImportContext ctx) throws SQLException {
        int count = 0;
        Map<Long, Long> commentIdMap = new HashMap<>();

        // 按 ID 排序以确保父评论先被处理（或者后续处理层级）
        String sql = "SELECT * FROM comments ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                long oldId = rs.getLong("id");
                String legacyStatus = columnExists(cols, "status") ? rs.getString("status") : "approved";
                if (legacyStatus == null) legacyStatus = "approved";
                if (columnExists(cols, "trash") && isTruthy(rs.getObject("trash"))) legacyStatus = "trash";
                else if (columnExists(cols, "spam") && isTruthy(rs.getObject("spam"))) legacyStatus = "spam";
                else if (columnExists(cols, "approved") && isTruthy(rs.getObject("approved"))) legacyStatus = "approved";
                if (isDeleted(rs, cols)) {
                    ctx.tracker().completeEntityItem("comments");
                    emitEntityProgress(ctx, "评论", "跳过已软删除评论");
                    continue;
                }
                long oldPostId = rs.getLong("post_id");
                Long newPostId = ctx.postMap().get(oldPostId);

                if (newPostId == null) {
                    ctx.tracker().completeEntityItem("comments");
                    emitEntityProgress(ctx, "评论", "评论处理中");
                    continue; // 文章不存在，跳过评论
                }

                Comment c = new Comment();
                c.content = processContentLinks(rs.getString("content"), assetPrefix, operator, ctx);
                c.status = mapLegacyCommentStatus(legacyStatus);
                c.auditStatus = c.status == 1 ? COMMENT_AUDIT_STATUS_APPROVED : (short) 0;
                c.guestName = trimToNull(firstAvailable(rs, cols, "guest_name", "author_name", "author"));
                c.guestEmail = normalizeEmail(firstAvailable(rs, cols, "guest_email", "email"));
                if ("trash".equalsIgnoreCase(legacyStatus)) {
                    c.deletedAt = readTimestamp(rs, cols, "updated_at");
                    if (c.deletedAt == null) c.deletedAt = OffsetDateTime.now();
                }

                Timestamp createdAtTs = columnExists(cols, "created_at") ? rs.getTimestamp("created_at") : null;
                if (createdAtTs != null)
                    c.createdAt = OffsetDateTime.ofInstant(createdAtTs.toInstant(), ZoneId.systemDefault());

                c.updatedAt = OffsetDateTime.now();

                // 处理父评论映射
                Long oldParentId = null;
                if (columnExists(cols, "parent_id")) {
                    long pid = rs.getLong("parent_id");
                    if (!rs.wasNull() && pid > 0) {
                        oldParentId = pid;
                    }
                }
                final Long finalParentId = oldParentId != null ? commentIdMap.get(oldParentId) : null;

                // 处理用户映射
                Long oldUserId = null;
                if (columnExists(cols, "user_id")) {
                    long uid = rs.getLong("user_id");
                    if (!rs.wasNull() && uid > 0) {
                        oldUserId = uid;
                    }
                }
                final Long finalUserId = oldUserId != null ? ctx.userMap().get(oldUserId) : null;
                if (finalUserId == null && oldUserId != null) {
                    String legacyAuthorName = ctx.authorNames().get(oldUserId);
                    if (legacyAuthorName != null && !legacyAuthorName.isBlank()) c.guestName = legacyAuthorName;
                }

                QuarkusTransaction.requiringNew().run(() -> {
                    c.post = Post.findById(newPostId);
                    if (finalParentId != null) {
                        c.parent = Comment.findById(finalParentId);
                    }
                    if (finalUserId != null) {
                        c.user = User.findById(finalUserId);
                    }
                    c.persist();
                });

                commentIdMap.put(oldId, c.id);
                ctx.tracker().completeEntityItem("comments");
                emitEntityProgress(ctx, "评论", "评论处理中");
                count++;
            }
        } catch (SQLException e) {
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            log.error("导入评论失败: " + safeMessage, e);
            emit("error", "导入评论失败: " + safeMessage, null);
            throw new SQLException("导入评论失败: " + safeMessage, e);
        }
        return count;
    }

    private PostStatus mapStatus(String status) {
        if ("published".equalsIgnoreCase(status) || "publish".equalsIgnoreCase(status)) return PostStatus.PUBLISHED;
        if ("draft".equalsIgnoreCase(status)) return PostStatus.DRAFT;
        if ("archived".equalsIgnoreCase(status)) return PostStatus.ARCHIVED;
        return PostStatus.DRAFT;
    }

    private short mapLegacyCommentStatus(String status) {
        if ("approved".equalsIgnoreCase(status) || "1".equals(status)) return 1;
        if ("spam".equalsIgnoreCase(status) || "trash".equalsIgnoreCase(status) || "2".equals(status)) return 2;
        return 0;
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value == null) return false;
        String normalized = value.toString().trim();
        return "1".equals(normalized) || "true".equalsIgnoreCase(normalized)
                || "yes".equalsIgnoreCase(normalized);
    }

    private String firstAvailable(ResultSet rs, List<String> columns, String... names) throws SQLException {
        for (String name : names) {
            if (columnExists(columns, name)) {
                String value = trimToNull(rs.getString(name));
                if (value != null) return value;
            }
        }
        return null;
    }

    private boolean isDeleted(ResultSet rs, List<String> columns) throws SQLException {
        if (columnExists(columns, "deleted_at") && rs.getTimestamp("deleted_at") != null) return true;
        if (columnExists(columns, "is_deleted") && isTruthy(rs.getObject("is_deleted"))) return true;
        if (columnExists(columns, "deleted") && isTruthy(rs.getObject("deleted"))) return true;
        if (columnExists(columns, "status")) {
            String status = rs.getString("status");
            return "trash".equalsIgnoreCase(status) || "deleted".equalsIgnoreCase(status);
        }
        return false;
    }

    private boolean isEnabled(ResultSet rs, List<String> columns) throws SQLException {
        if (!columnExists(columns, "status")) return true;
        Object status = rs.getObject("status");
        if (status == null) return true;
        if (status instanceof Boolean value) return value;
        if (status instanceof Number value) return value.intValue() != 0;
        String value = status.toString().trim();
        return !("false".equalsIgnoreCase(value) || "0".equals(value)
                || "disabled".equalsIgnoreCase(value) || "hidden".equalsIgnoreCase(value));
    }

    private OffsetDateTime readTimestamp(ResultSet rs, List<String> columns, String column) throws SQLException {
        if (!columnExists(columns, column)) return null;
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeEmail(String value) {
        String email = trimToNull(value);
        if (email == null || !email.matches("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return null;
        return email.toLowerCase(java.util.Locale.ROOT);
    }

    private short mapVisibility(String visibility) {
        if ("public".equalsIgnoreCase(visibility)) return 0;
        if ("private".equalsIgnoreCase(visibility)) return 1;
        if ("password".equalsIgnoreCase(visibility)) return 2;
        return 0;
    }

    private PostRenderType mapRenderType(String type) {
        if ("html".equalsIgnoreCase(type)) return PostRenderType.HTML;
        return PostRenderType.MARKDOWN;
    }

    private String sanitizeImportSlug(String rawSlug, String fallbackName, String fallbackPrefix, long fallbackId) {
        String input = rawSlug;
        if (input != null && input.contains("%")) {
            try {
                input = URLDecoder.decode(input, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }

        String slug = SlugHelper.slugify(input);
        if (slug.isEmpty()) {
            slug = SlugHelper.slugify(fallbackName);
        }
        if (slug.isEmpty()) {
            slug = fallbackPrefix + fallbackId;
        }
        return slug;
    }

    private List<String> getAvailableColumns(ResultSet rs) throws SQLException {
        List<String> columns = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        for (int i = 1; i <= count; i++) {
            columns.add(meta.getColumnLabel(i).toLowerCase());
        }
        return columns;
    }

    private boolean columnExists(List<String> columns, String columnName) {
        if (columnName == null) {
            return false;
        }
        return columns.contains(columnName.toLowerCase());
    }

    private String processContentLinks(String content, String assetPrefix, User operator, ImportContext ctx) {
        if (content == null || content.isBlank()) return content;

        // 先迁移所有上下文中的 image Data URL（Markdown、HTML、CSS url() 及普通文本），
        // 保留原有换行和 Markdown，只替换实际的 data:image/... token。
        content = replaceEmbeddedDataImages(content, operator, ctx);

        StringBuilder sb;
        int lastEnd;

        // 1. Markdown 图片: ![]()
        Matcher mdImgMatcher = MD_IMG_PATTERN.matcher(content);
        sb = new StringBuilder();
        lastEnd = 0;
        while (mdImgMatcher.find()) {
            sb.append(content, lastEnd, mdImgMatcher.start());
            String prefix = mdImgMatcher.group(1);
            String url = mdImgMatcher.group(2);
            String newUrl = resolveAndDownload(url, assetPrefix, operator, ctx);
            sb.append(prefix).append("(").append(newUrl).append(")");
            lastEnd = mdImgMatcher.end();
        }
        sb.append(content.substring(lastEnd));
        content = sb.toString();

        // 2. Markdown 普通链接: [text](url) - 排除图片
        Matcher mdLinkMatcher = MD_LINK_PATTERN.matcher(content);
        sb = new StringBuilder();
        lastEnd = 0;
        while (mdLinkMatcher.find()) {
            sb.append(content, lastEnd, mdLinkMatcher.start());
            String text = mdLinkMatcher.group(2);
            String url = mdLinkMatcher.group(3);
            String newUrl = resolveExternalLink(url, text, assetPrefix, ctx);
            sb.append("[").append(text).append("](").append(newUrl).append(")");
            lastEnd = mdLinkMatcher.end();
        }
        sb.append(content.substring(lastEnd));
        content = sb.toString();

        // 3. HTML <img>: <img src="...">
        Matcher htmlImgMatcher = HTML_IMG_PATTERN.matcher(content);
        sb = new StringBuilder();
        lastEnd = 0;
        while (htmlImgMatcher.find()) {
            sb.append(content, lastEnd, htmlImgMatcher.start());
            String prefix = htmlImgMatcher.group(1);
            String url = htmlImgMatcher.group(2);
            String suffix = htmlImgMatcher.group(3);
            String newUrl = resolveAndDownload(url, assetPrefix, operator, ctx);
            sb.append(prefix).append(newUrl).append(suffix);
            lastEnd = htmlImgMatcher.end();
        }
        sb.append(content.substring(lastEnd));
        content = sb.toString();

        // 4. HTML <a>: <a href="...">
        Matcher htmlLinkMatcher = HTML_LINK_PATTERN.matcher(content);
        sb = new StringBuilder();
        lastEnd = 0;
        while (htmlLinkMatcher.find()) {
            sb.append(content, lastEnd, htmlLinkMatcher.start());
            String prefix = htmlLinkMatcher.group(1);
            String url = htmlLinkMatcher.group(2);
            String suffix = htmlLinkMatcher.group(3);
            String newUrl = resolveExternalLink(url, null, assetPrefix, ctx);
            sb.append(prefix).append(newUrl).append(suffix);
            lastEnd = htmlLinkMatcher.end();
        }
        sb.append(content.substring(lastEnd));

        return sb.toString();
    }

    private String resolveExternalLink(String url, String text, String assetPrefix, ImportContext ctx) {
        if (url == null || url.isBlank() || url.startsWith("#") || isNonMediaScheme(url)) {
            return url;
        }

        // 普通外链和站内绝对路径保持原样，不再隐式创建 /go 重定向记录。
        if (isAbsoluteHttpUrl(url) || url.startsWith("/") || url.startsWith("//")) {
            return url;
        }

        // 查找映射表
        if (ctx.urlMap().containsKey(url)) {
            return ctx.urlMap().get(url);
        }

        // 非根相对链接使用来源站点基址补全；没有合法基址时保留原值，避免拼出不可访问 URL。
        String resolved = resolveRelativeUrl(assetPrefix, url);
        ctx.urlMap().put(url, resolved);
        return resolved;
    }

    private String resolveAndDownload(String url, String assetPrefix, User operator, ImportContext ctx) {
        if (url == null || url.isBlank()) {
            return url;
        }

        // 如果在映射表中，直接返回
        String cachedUrl = ctx.urlMap().get(url);
        if (cachedUrl != null) {
            return cachedUrl;
        }

        if (isEmbeddedImageDataUrl(url)) return importEmbeddedImage(url, operator, ctx);
        if (isNonMediaScheme(url)) return url;

        // 图片和媒体先改写到可持久化的站内占位地址，外部网络请求延后处理。
        String fullUrl = isAbsoluteHttpUrl(url) ? url : resolveRelativeUrl(assetPrefix, url);
        if (!isAbsoluteHttpUrl(fullUrl)) return url;
        registerDeferredMedia(fullUrl, List.of(url, fullUrl), operator, ctx);
        return ctx.urlMap().getOrDefault(url, ctx.urlMap().getOrDefault(fullUrl, url));
    }

    private boolean isNonMediaScheme(String url) {
        String normalized = url.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("data:") || normalized.startsWith("mailto:")
                || normalized.startsWith("javascript:") || normalized.startsWith("tel:");
    }

    private boolean isEmbeddedImageDataUrl(String url) {
        return url != null && url.trim().regionMatches(true, 0, "data:image/", 0, 11);
    }

    private String importEmbeddedImage(String dataUrl, User operator, ImportContext ctx) {
        String normalized = dataUrl.trim();
        String cached = ctx.embeddedMap().get(normalized);
        if (cached != null) return cached;
        EmbeddedDataImageService.ParsedImage parsed = EmbeddedDataImageService.parse(normalized);
        String byHash = ctx.embeddedMap().get("sha256:" + parsed.sha256());
        if (byHash != null) {
            ctx.embeddedMap().put(normalized, byHash);
            return byHash;
        }
        try {
            Media media = embeddedDataImageService.importImage(normalized, operator);
            ctx.embeddedMap().put(normalized, media.url);
            ctx.embeddedMap().put("sha256:" + parsed.sha256(), media.url);
            return media.url;
        } catch (Exception exception) {
            throw new IllegalArgumentException("内嵌图片导入失败：" + sanitizeErrorMessage(exception.getMessage()), exception);
        }
    }

    private String replaceEmbeddedDataImages(String content, User operator, ImportContext ctx) {
        Matcher matcher = EmbeddedDataImageService.DATA_IMAGE_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = importEmbeddedImage(matcher.group(), operator, ctx);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean isAbsoluteHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String resolveRelativeUrl(String assetPrefix, String path) {
        String normalizedPrefix = normalizeAssetPrefix(assetPrefix);
        if (normalizedPrefix.isBlank()) return path;
        try {
            URI base = URI.create(normalizedPrefix.endsWith("/") ? normalizedPrefix : normalizedPrefix + "/");
            URI relative = URI.create(path.trim());
            if (relative.getScheme() == null && path.startsWith("//")) {
                relative = URI.create(base.getScheme() + ":" + path.trim());
            }
            return base.resolve(relative).toString();
        } catch (IllegalArgumentException ignored) {
            return path;
        }
    }

    private String normalizeAssetPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "";
        try {
            URI uri = new URI(prefix.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                return "";
            }
            return uri.toString();
        } catch (URISyntaxException ignored) {
            return "";
        }
    }

    private record DeferredMediaImport(String sourceUrl, Long mediaId, String placeholderUrl) {
    }

    private static final class DownloadState {
        private long downloadedBytes;
        private long totalBytes = -1L;
        private int statusCode;
        private int redirectCount;
        private String error;
        private long lastReportedBytes;
        private long lastReportedNanos;

        private boolean shouldReport(long downloaded, long total) {
            long now = System.nanoTime();
            boolean complete = total > 0 && downloaded >= total;
            boolean enoughBytes = downloaded - lastReportedBytes >= 64 * 1024L;
            boolean enoughTime = now - lastReportedNanos >= 200_000_000L;
            if (complete || enoughBytes || enoughTime || lastReportedNanos == 0L) {
                lastReportedBytes = downloaded;
                lastReportedNanos = now;
                return true;
            }
            return false;
        }
    }

    private static final class ImportProgressTracker {
        private static final long PROGRESS_REPORT_INTERVAL_NANOS = 250_000_000L;
        private final Map<String, Long> entityTotals = new HashMap<>();
        private final Map<String, Long> completedEntityItems = new HashMap<>();
        private final Set<String> resources = new java.util.LinkedHashSet<>();
        private final Set<String> completedResources = new HashSet<>();
        private final Set<String> successfulResources = new HashSet<>();
        private final Set<String> failedResources = new HashSet<>();
        private long lastProgressReportNanos;

        void initialize(Connection connection, List<String> types) {
            for (String type : types) {
                if (!List.of("categories", "tags", "posts", "media", "links", "comments").contains(type)) {
                    continue;
                }
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM \"" + type + "\"")) {
                    if (result.next()) entityTotals.put(type, Math.max(0L, result.getLong(1)));
                } catch (SQLException ignored) {
                    entityTotals.put(type, 0L);
                }
            }
        }

        void completeEntityType(String type) {
            completedEntityItems.put(type, entityTotals.getOrDefault(type, 0L));
        }

        void completeEntityItem(String type) {
            long total = entityTotals.getOrDefault(type, 0L);
            long completed = completedEntityItems.getOrDefault(type, 0L);
            completedEntityItems.put(type, Math.min(total, completed + 1L));
        }

        boolean shouldReportProgress() {
            long now = System.nanoTime();
            if (now - lastProgressReportNanos < PROGRESS_REPORT_INTERVAL_NANOS) {
                return false;
            }
            lastProgressReportNanos = now;
            return true;
        }

        void registerResource(String url) {
            resources.add(url);
        }

        int resourceIndex(String url) {
            int index = 1;
            for (String resource : resources) {
                if (resource.equals(url)) return index;
                index++;
            }
            return index;
        }

        void completeResource(String url, boolean success) {
            completedResources.add(url);
            if (success) successfulResources.add(url); else failedResources.add(url);
        }

        int resourceTotal() { return resources.size(); }
        int completedResources() { return completedResources.size(); }
        int successfulResources() { return successfulResources.size(); }
        int failedResources() { return failedResources.size(); }

        Map<String, Object> snapshot() {
            long totalEntityUnits = entityTotals.values().stream().mapToLong(Long::longValue).sum();
            long completedEntityUnits = completedEntityItems.values().stream()
                    .mapToLong(Long::longValue).sum();
            long total = totalEntityUnits + resources.size();
            long completed = completedEntityUnits + completedResources.size();
            double percent = total <= 0 ? 0d : Math.min(100d, completed * 100d / total);
            Map<String, Object> result = new HashMap<>();
            result.put("completed", completed);
            result.put("total", total);
            result.put("percent", percent);
            result.put("completedEntities", completedEntityUnits);
            result.put("totalEntities", totalEntityUnits);
            result.put("completedResources", completedResources.size());
            result.put("totalResources", resources.size());
            result.put("successfulResources", successfulResources.size());
            result.put("failedResources", failedResources.size());
            return result;
        }
    }

    // 导入上下文，用于在方法间传递状态
    private record ImportContext(
            Map<String, String> urlMap,
            Map<String, String> embeddedMap,
            Map<String, DeferredMediaImport> deferredMedia,
            Map<Long, Long> categoryMap,
            Map<Long, Long> tagMap,
            Map<Long, Long> userMap,
            Map<Long, String> authorNames,
            Map<Long, Long> postMap,
            ImportProgressTracker tracker
    ) {
        public ImportContext() {
            this(new HashMap<>(), new HashMap<>(), new LinkedHashMap<>(), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>(), new HashMap<>(), new ImportProgressTracker());
        }
    }
}
