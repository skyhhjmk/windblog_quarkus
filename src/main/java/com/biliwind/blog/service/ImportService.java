package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.SlugHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportProgressEvent;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportResult;
import com.biliwind.blog.common.helper.MediaPathHelper;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Inject
    MediaManagementService mediaService;

    @Inject
    ImportAnalysisService importAnalysisService;

    @Inject
    EmbeddedDataImageService embeddedDataImageService;

    @Inject
    DataSource targetDataSource;

    private static final List<String> IMPORT_TABLES = List.of(
            "categories", "tags", "posts", "post_category", "post_tag", "post_author",
            "links", "comments", "media", "wa_users");
    private static final Pattern SQL_TARGET_TABLE = Pattern.compile(
            "(?is)^(?:insert\\s+into|create\\s+(?:temporary\\s+)?table)\\s+"
                    + "(?:if\\s+not\\s+exists\\s+)?(?:[\\\"`\\w]+\\.)?[\\\"`]?([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * 执行数据导入
     */
    public ImportResult doImport(ImportRequest req, Long operatorId) {
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

    public ImportResult doImportSql(java.nio.file.Path sqlFile, ImportRequest req, Long operatorId) {
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
        if (types.contains("media")) {
            emit("info", "正在导入媒体库...", null);
            importMedia(conn, req.assetPrefix(), operator, ctx);
            ctx.tracker().completeEntityType("media");
            emitOverall(ctx, "媒体", "媒体资源处理完成");
            emit("progress", "媒体库导入主体完成", null);
        }
        emit("info", "正在处理作者/用户映射...", null);
        importUsers(conn, ctx);
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
        if (!ctx.retryQueue().isEmpty()) {
            emit("info", "正在执行附件重试任务 (" + ctx.retryQueue().size() + " 个)...", null);
            processRetryQueue(operator, ctx);
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
                        throw new IllegalArgumentException("表 " + table + " 只支持 INSERT ... VALUES 导入，不允许执行查询或附加 SQL");
                    }
                    String rewritten = sql.replaceFirst(
                            "(?is)^(insert\\s+into\\s+)(?:[\\\"`\\w]+\\.)?[\\\"`]?" + table + "[\\\"`]?",
                            "$1" + table);
                    statement.execute(rewritten);
                } else if (sql.regionMatches(true, 0, "COPY", 0, 4)) {
                    throw new IllegalArgumentException("SQL 文件包含 COPY 导出，请先转换为 INSERT 格式");
                }
            }
        }
    }

    private void ensureTemporaryTable(java.sql.Statement statement, String table,
            java.util.Set<String> materializedTables) throws SQLException {
        if (materializedTables.contains(table)) return;
        try {
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS " + table
                    + " AS SELECT * FROM public." + table + " WITH NO DATA");
            materializedTables.add(table);
        } catch (SQLException exception) {
            throw new IllegalArgumentException("SQL 文件缺少表 " + table
                    + " 的定义，且当前数据库无法提供兼容表结构");
        }
    }

    private boolean isSimpleInsert(String sql, String table) {
        String normalized = sql.trim();
        String target = "(?is)^insert\\s+into\\s+(?:[\\\"`\\w]+\\.)?[\\\"`]?"
                + Pattern.quote(table) + "[\\\"`]?\\s*(?:\\([^;]*?\\))?\\s+values\\s+.+$";
        if (!normalized.matches(target)) return false;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains(" returning ") && !lower.contains(" on conflict ");
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
                String categoryName = rs.getString("name");
                String slug = sanitizeImportSlug(rs.getString("slug"), categoryName, "cat-", oldId);

                Category existing = Category.find("slug = ?1", slug).firstResult();
                if (existing != null) {
                    emit("info", "跳过已存在的分类: " + categoryName, slug);
                    ctx.categoryMap().put(oldId, existing.id);
                    continue;
                }

                final Category c = new Category();
                c.slug = slug;
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
                count++;
            }
        }
        return count;
    }

    public Multi<ImportProgressEvent> getEventStream() {
        return eventProcessor;
    }

    private void emit(String type, String message, Object data) {
        eventProcessor.onNext(new ImportProgressEvent(type, message, data, null));
    }

    private void emit(String type, String message, Object data, String status) {
        eventProcessor.onNext(new ImportProgressEvent(type, message, data, status));
    }

    private void emitOverall(ImportContext ctx, String phase, String message) {
        Map<String, Object> data = new HashMap<>(ctx.tracker().snapshot());
        data.put("phase", phase);
        emit("overall", message, data, "processing");
    }

    private Media downloadImportedMedia(User operator, String fullUrl, ImportContext ctx) throws Exception {
        ImportProgressTracker tracker = ctx.tracker();
        tracker.registerResource(fullUrl);
        int resourceIndex = tracker.resourceIndex(fullUrl);
        DownloadState state = new DownloadState();
        emit("download", "开始下载资源 " + sanitizeErrorMessage(fullUrl),
                downloadData(tracker, fullUrl, resourceIndex, state, "started"), "processing");
        try {
            Media media = mediaService.importFromUrl(operator, fullUrl,
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
        String sql = "SELECT * FROM tags";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                long oldId = rs.getLong("id");
                String tagName = rs.getString("name");
                String slug = sanitizeImportSlug(rs.getString("slug"), tagName, "tag-", System.currentTimeMillis());

                Tag existing = Tag.find("slug = ?1", slug).firstResult();
                if (existing != null) {
                    emit("info", "跳过已存在的标签: " + tagName, slug);
                    ctx.tagMap().put(oldId, existing.id);
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
                count++;
            }
        }
        return count;
    }

    private void importMedia(Connection conn, String assetPrefix, User operator, ImportContext ctx) throws SQLException {
        // 发现池，Key 是探测到的各种路径形式，Value 是对应的下载 URL
        Map<String, String> discoveryMap = new HashMap<>();

        // 1. 从 media 表中发现
        String mediaSql = "SELECT * FROM media WHERE deleted_at IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(mediaSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String filePath = rs.getString("file_path");
                if (filePath == null) continue;

                if (isEmbeddedImageDataUrl(filePath)) {
                    String imported = importEmbeddedImage(filePath, operator, ctx);
                    ctx.urlMap().put(filePath, imported);
                    continue;
                }

                boolean absoluteSource = isAbsoluteHttpUrl(filePath);
                String oldRelUrl = absoluteSource ? filePath : normalizeOldRelUrl(filePath);
                String fullUrl = absoluteSource ? filePath : formatUrl(assetPrefix, oldRelUrl);

                discoveryMap.put(oldRelUrl, fullUrl);
                discoveryMap.put(filePath, fullUrl);
                if (!filePath.startsWith("/")) discoveryMap.put("/" + filePath, fullUrl);
            }
        } catch (SQLException e) {
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            emit("info", "读取 media 表失败，将仅依赖文章内容分析: " + safeMessage, null);
        }

        // 2. 从 posts 表中通过内容分析发现
        extractUrlsFromPosts(conn, assetPrefix, discoveryMap);

        // 3. 执行去重后的下载任务
        // 我们需要按 fullUrl 进行分组，避免同一个文件因为不同的引用路径被下载多次
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
            ctx.tracker().registerResource(fullUrl);

            String safeUrl = sanitizeErrorMessage(fullUrl);
            emit("info", "同步媒体资源: " + safeUrl, null);
            try {
                Media m = downloadImportedMedia(operator, fullUrl, ctx);
                // 将所有关联的引用路径都指向新 URL
                for (String path : refPaths) {
                    ctx.urlMap().put(path, m.url);
                }
                ctx.urlMap().put(fullUrl, m.url);
            } catch (Exception e) {
                String safeMessage = sanitizeErrorMessage(e.getMessage());
                emit("error", "同步失败，创建占位记录: " + safeUrl, safeMessage);
                Media failedMedia = mediaService.markAsImportFailed(operator, fullUrl, safeMessage);
                for (String path : refPaths) {
                    ctx.urlMap().put(path, failedMedia.url);
                }
                ctx.urlMap().put(fullUrl, failedMedia.url);
            }
        }
    }

    private void extractUrlsFromPosts(Connection conn, String assetPrefix, Map<String, String> discoveryMap) throws SQLException {
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

        String sql = "SELECT * FROM posts WHERE deleted_at IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                String title = rs.getString("title");
                String slug = sanitizeImportSlug(rs.getString("slug"), title, "post-", rs.getLong("id"));

                if (Post.count("slug = ?1", slug) > 0) {
                    emit("info", "跳过已存在的文章: " + title, slug);
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

                Timestamp publishedAtTs = rs.getTimestamp("published_at");
                if (publishedAtTs != null)
                    p.publishedAt = OffsetDateTime.ofInstant(publishedAtTs.toInstant(), ZoneId.systemDefault());

                Timestamp createdAtTs = rs.getTimestamp("created_at");
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

                ctx.postMap().put(oldPostId, p.id);
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

    private void processRetryQueue(User operator, ImportContext ctx) {
        List<DownloadTask> currentQueue = new ArrayList<>(ctx.retryQueue());
        ctx.retryQueue().clear();

        for (DownloadTask task : currentQueue) {
            String safeUrl = sanitizeErrorMessage(task.sourceUrl);
            emit("info", "重试下载: " + safeUrl, null);
            try {
                Media m = downloadImportedMedia(operator, task.sourceUrl, ctx);
                ctx.urlMap().put(task.sourceUrl, m.url);
            } catch (Exception e) {
                String safeMessage = sanitizeErrorMessage(e.getMessage());
                emit("error", "重试仍然失败: " + safeUrl, safeMessage);
                // 最终失败时，resolveAndDownload 已创建失败记录，直接使用已有记录的 URL
                ctx.urlMap().put(task.sourceUrl, task.sourceUrl);
            }
        }
    }

    private int importLinks(Connection conn, User operator, String assetPrefix, ImportContext ctx) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM links";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                String url = rs.getString("url");
                if (Link.count("url = ?1", url) > 0) continue;

                final Link l = new Link();
                l.url = url;
                l.name = rs.getString("name");
                if (columnExists(cols, "description")) l.description = processContentLinks(rs.getString("description"), assetPrefix, operator, ctx);
                if (columnExists(cols, "image")) l.image = resolveAndDownload(rs.getString("image"), assetPrefix, operator, ctx);
                if (columnExists(cols, "icon")) l.icon = resolveAndDownload(rs.getString("icon"), assetPrefix, operator, ctx);
                if (columnExists(cols, "sort_order")) l.sortOrder = rs.getInt("sort_order");
                l.status = 1; // Default visible
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
                count++;
            }
        }
        return count;
    }

    private void importUsers(Connection conn, ImportContext ctx) throws SQLException {
        // 在 windblog_webman 中，作者表通常是 wa_users
        String sql = "SELECT id, username, nickname, email FROM wa_users";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long oldId = rs.getLong("id");
                String username = rs.getString("username");
                String nickname = rs.getString("nickname");
                String email = rs.getString("email");

                // 尝试通过用户名或邮箱查找现有用户
                User existing = User.find("username = ?1 or email = ?2", username, email).firstResult();
                if (existing != null) {
                    ctx.userMap().put(oldId, existing.id);
                } else {
                    // 如果不存在，可以考虑自动创建，但目前为了安全，仅做映射
                    // 也可以映射给当前操作者，或者映射给超级管理员
                }
            }
        } catch (SQLException e) {
            emit("info", "未找到旧系统的用户表 (wa_users)，将跳过作者映射: "
                    + sanitizeErrorMessage(e.getMessage()), null);
        }
    }

    private void importPostRelations(Connection conn, ImportContext ctx) throws SQLException {
        // 预加载文章和分类，避免 N+1 查询
        Map<Long, Post> postsById = new HashMap<>();
        if (!ctx.postMap().isEmpty()) {
            java.util.Set<Long> postIds = new java.util.HashSet<>(ctx.postMap().values());
            List<Post> postList = Post.list("id in ?1", postIds);
            for (Post p : postList) {
                postsById.put(p.id, p);
            }
        }
        Map<Long, Category> categoriesById = new HashMap<>();
        if (!ctx.categoryMap().isEmpty()) {
            java.util.Set<Long> catIds = new java.util.HashSet<>(ctx.categoryMap().values());
            List<Category> catList = Category.list("id in ?1", catIds);
            for (Category c : catList) {
                categoriesById.put(c.id, c);
            }
        }
        Map<Long, User> usersById = new HashMap<>();
        if (!ctx.userMap().isEmpty()) {
            java.util.Set<Long> userIds = new java.util.HashSet<>(ctx.userMap().values());
            List<User> userList = User.list("id in ?1", userIds);
            for (User u : userList) {
                usersById.put(u.id, u);
            }
        }

        // 1. 迁移分类关联 (post_category)
        // 注意：新系统 Post 实体目前仅支持一个 category_id
        String catSql = "SELECT post_id, category_id FROM post_category";
        try (PreparedStatement ps = conn.prepareStatement(catSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long oldPostId = rs.getLong("post_id");
                long oldCatId = rs.getLong("category_id");

                Long newPostId = ctx.postMap().get(oldPostId);
                Long newCatId = ctx.categoryMap().get(oldCatId);

                if (newPostId != null && newCatId != null) {
                    QuarkusTransaction.requiringNew().run(() -> {
                        Post p = postsById.get(newPostId);
                        Category c = categoriesById.get(newCatId);
                        if (p != null && c != null && p.category == null) {
                            p.category = c;
                            p.persist();
                        }
                    });
                }
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
                        Post p = postsById.get(newPostId);
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
            }
        } catch (SQLException e) {
            emit("info", "处理标签关联时跳过 (可能表不存在): "
                    + sanitizeErrorMessage(e.getMessage()), null);
        }

        // 3. 迁移作者关联 (post_author)
        String authSql = "SELECT post_id, author_id FROM post_author WHERE is_primary = true";
        try (PreparedStatement ps = conn.prepareStatement(authSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long oldPostId = rs.getLong("post_id");
                long oldAuthId = rs.getLong("author_id");

                Long newPostId = ctx.postMap().get(oldPostId);
                Long newAuthId = ctx.userMap().get(oldAuthId);

                if (newPostId != null && newAuthId != null) {
                    QuarkusTransaction.requiringNew().run(() -> {
                        Post p = postsById.get(newPostId);
                        User u = usersById.get(newAuthId);
                        if (p != null && u != null) {
                            p.user = u;
                            p.persist();
                        }
                    });
                }
            }
        } catch (SQLException e) {
            // 可能没有 post_author 表，或者是 wa_posts 里直接带 author_id
        }
    }

    private int importComments(Connection conn, User operator, String assetPrefix, ImportContext ctx) throws SQLException {
        int count = 0;
        Map<Long, Long> commentIdMap = new HashMap<>();

        // 预加载文章和用户，避免 N+1 查询
        Map<Long, Post> postsById = new HashMap<>();
        if (!ctx.postMap().isEmpty()) {
            java.util.Set<Long> postIds = new java.util.HashSet<>(ctx.postMap().values());
            List<Post> postList = Post.list("id in ?1", postIds);
            for (Post p : postList) {
                postsById.put(p.id, p);
            }
        }
        Map<Long, User> usersById = new HashMap<>();
        if (!ctx.userMap().isEmpty()) {
            java.util.Set<Long> userIds = new java.util.HashSet<>(ctx.userMap().values());
            List<User> userList = User.list("id in ?1", userIds);
            for (User u : userList) {
                usersById.put(u.id, u);
            }
        }

        // 按 ID 排序以确保父评论先被处理（或者后续处理层级）
        String sql = "SELECT * FROM comments ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> cols = getAvailableColumns(rs);
            while (rs.next()) {
                long oldId = rs.getLong("id");
                long oldPostId = rs.getLong("post_id");
                Long newPostId = ctx.postMap().get(oldPostId);

                if (newPostId == null) {
                    continue; // 文章不存在，跳过评论
                }

                Comment c = new Comment();
                c.content = processContentLinks(rs.getString("content"), assetPrefix, operator, ctx);
                c.status = 1; // 默认通过
                c.auditStatus = COMMENT_AUDIT_STATUS_APPROVED;

                Timestamp createdAtTs = rs.getTimestamp("created_at");
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

                QuarkusTransaction.requiringNew().run(() -> {
                    c.post = postsById.get(newPostId);
                    if (finalParentId != null) {
                        c.parent = Comment.findById(finalParentId);
                    }
                    if (finalUserId != null) {
                        c.user = usersById.get(finalUserId);
                    }
                    c.persist();
                });

                commentIdMap.put(oldId, c.id);
                count++;
            }
        } catch (SQLException e) {
            emit("error", "导入评论失败: " + sanitizeErrorMessage(e.getMessage()), null);
        }
        return count;
    }

    private PostStatus mapStatus(String status) {
        if ("published".equalsIgnoreCase(status)) return PostStatus.PUBLISHED;
        if ("draft".equalsIgnoreCase(status)) return PostStatus.DRAFT;
        if ("archived".equalsIgnoreCase(status)) return PostStatus.ARCHIVED;
        return PostStatus.DRAFT;
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

        // 相对路径、协议相对 URL，以及来源站点同源资源才进入媒体下载流程。
        boolean isRelativeOrOldSystem = !isAbsoluteHttpUrl(url) || isSameOrigin(url, assetPrefix);

        if (isRelativeOrOldSystem) {
            String fullUrl = url;
            if (!isAbsoluteHttpUrl(url)) {
                fullUrl = resolveRelativeUrl(assetPrefix, url);
            }

            if (!isAbsoluteHttpUrl(fullUrl)) return url;

            // 再次检查拼接后的完整 URL 是否在映射中
            String cachedFullUrl = ctx.urlMap().get(fullUrl);
            if (cachedFullUrl != null) {
                return cachedFullUrl;
            }

            try {
                Media m = downloadImportedMedia(operator, fullUrl, ctx);
                ctx.urlMap().put(url, m.url);
                ctx.urlMap().put(fullUrl, m.url);
                return m.url;
            } catch (Exception e) {
                // 下载失败时立即创建失败占位记录，避免后续重复尝试
                Media failedMedia = mediaService.markAsImportFailed(
                        operator, fullUrl, sanitizeErrorMessage(e.getMessage()));
                ctx.urlMap().put(url, failedMedia.url);
                ctx.urlMap().put(fullUrl, failedMedia.url);
                return failedMedia.url;
            }
        }

        return url;
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

    private boolean isSameOrigin(String url, String assetPrefix) {
        try {
            URI source = URI.create(url);
            URI base = URI.create(normalizeAssetPrefix(assetPrefix));
            return source.getHost() != null && source.getHost().equalsIgnoreCase(base.getHost())
                    && source.getPort() == base.getPort()
                    && source.getScheme().equalsIgnoreCase(base.getScheme());
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

    private record DownloadTask(String sourceUrl, String targetName, Post relatedPost) {
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
        private final Map<String, Long> entityTotals = new HashMap<>();
        private final Set<String> completedEntityTypes = new HashSet<>();
        private final Set<String> resources = new java.util.LinkedHashSet<>();
        private final Set<String> completedResources = new HashSet<>();
        private final Set<String> successfulResources = new HashSet<>();
        private final Set<String> failedResources = new HashSet<>();

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
            completedEntityTypes.add(type);
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
            long completedEntityUnits = completedEntityTypes.stream()
                    .mapToLong(type -> entityTotals.getOrDefault(type, 0L)).sum();
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
            List<DownloadTask> retryQueue,
            Map<Long, Long> categoryMap,
            Map<Long, Long> tagMap,
            Map<Long, Long> userMap,
            Map<Long, Long> postMap,
            ImportProgressTracker tracker
    ) {
        public ImportContext() {
            this(new HashMap<>(), new HashMap<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new ImportProgressTracker());
        }
    }
}
