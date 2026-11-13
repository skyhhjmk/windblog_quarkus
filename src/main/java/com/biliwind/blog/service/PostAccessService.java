package com.biliwind.blog.service;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.UserPurchaseRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

/**
 * 预留访问控制服务层。
 * 当前使用 Active Record 方式，后续逐步迁移至 Service 分层。
 */
@ApplicationScoped
public class PostAccessService {

    private static final java.util.regex.Pattern HIDDEN_ATTACHMENT_PATTERN = java.util.regex.Pattern.compile(
            "\\[\\s*hide-attachment(.*?)\\](.*?)\\[\\s*/hide-attachment\\s*\\]",
            java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern MARKDOWN_ATTACHMENT_LINK = java.util.regex.Pattern.compile(
            "\\[([^\\]]*)\\]\\(\\s*<?([^\\s)>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final java.util.regex.Pattern HTML_ATTACHMENT_LINK = java.util.regex.Pattern.compile(
            "<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);

    // Authored content is sanitized segment by segment; server-owned templates are
    // appended afterwards so the sanitizer can never strip their purchase controls.
    private static final java.util.regex.Pattern HIDDEN_BLOCK_PATTERN = java.util.regex.Pattern.compile(
            "\\[\\s*(hide-text|hide-attachment)\\b(.*?)\\](.*?)\\[\\s*/\\s*\\1\\s*\\]",
            java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HIDDEN_BLOCK_OPEN_PATTERN = java.util.regex.Pattern.compile(
            "\\[\\s*(hide-text|hide-attachment)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern INLINE_TEMPLATE_PATTERN = java.util.regex.Pattern.compile(
            "\\[\\s*store-item\\s+id\\s*=\\s*(?:\"([^\"]*)\"|([^\\s\\]]+))\\s*\\]"
                    + "|\\[\\s*hide-image\\s+url\\s*=\\s*\"([^\"]+)\"\\s*\\]"
                    + "|\\[\\s*attachment\\s+id\\s*=\\s*\"([^\"]+)\"\\s*\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String LEGACY_HIDE_IMAGE_HTML =
            "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div>"
                    + "<div class=\"md-region-content\"><strong>付费图片已隐藏</strong>：购买文章后解锁。</div></div>\n\n";
    private static final String LEGACY_ATTACHMENT_HTML =
            "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div>"
                    + "<div class=\"md-region-content\"><strong>专属附件已隐藏</strong>：购买文章后解锁。</div></div>\n\n";

    /**
     * A [hide-attachment] shortcode. Free blocks still require a login; priced
     * blocks require the matching block purchase (or the whole article).
     */
    public record HiddenAttachmentBlock(String blockId, String name, long price,
                                        String content, String url) {
    }

    /** Sidebar projection for an attachment and its current unlock state. */
    public record AttachmentItem(String blockId, String name, long price, boolean unlocked,
                                 boolean loginRequired, String downloadUrl, String formattedSize) {
    }

    @Inject
    @io.quarkus.qute.Location("system/components/premium_card.html")
    io.quarkus.qute.Template premiumCardTemplate;

    @Inject
    @io.quarkus.qute.Location("system/components/premium_attachment.html")
    io.quarkus.qute.Template premiumAttachmentTemplate;

    @Inject
    @io.quarkus.qute.Location("system/components/hide_block.html")
    io.quarkus.qute.Template hideBlockTemplate;

    @Inject
    com.biliwind.blog.i18n.I18n i18n;

    @Inject
    @io.quarkus.qute.Location("system/components/store_item_card.html")
    io.quarkus.qute.Template storeItemCardTemplate;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    ContentAccessTicketService contentAccessTicketService;

    @Inject
    PublicContentSanitizer publicContentSanitizer;

    public Post findBySlug(String slug) {
        return Post.find("slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                        slug,
                        com.biliwind.blog.model.PostStatus.PUBLISHED)
                .firstResult();
    }

    /**
     * Public HTML uses the stable slug instead of exposing the database key.
     * Internal authorization and purchase records continue to use the numeric ID.
     */
    private String resolvePostRef(Long postId) {
        if (postId == null) {
            return null;
        }
        Post post = Post.findById(postId);
        return post == null ? null : post.slug;
    }

    public boolean isPrivate(Post post) {
        return post.visibility == 1;
    }

    public boolean isPasswordProtected(Post post) {
        return post.visibility == 2;
    }

    /**
     * A post with any non-public visibility or a positive full-article price
     * must not expose its media through the public upload path.
     */
    public boolean isProtectedPost(Post post) {
        if (post == null) {
            return false;
        }
        return post.visibility != 0 || getPostPrice(post) > 0;
    }

    /**
     * Keep the historical attachment marker protected while also covering
     * inline images and other media references in protected posts. Media that
     * is only linked from a [hide-attachment] block is protected as well: a
     * free attachment still requires a login, so its raw /uploads URL must not
     * bypass the content gate.
     */
    public boolean isProtectedMediaReference(PostMedia reference) {
        if (reference == null) {
            return false;
        }
        return reference.usageType == 3 || isProtectedPost(reference.post)
                || isMediaInsideHiddenAttachment(reference.post, reference.media);
    }

    /** All attachment blocks of a post, across every published language. */
    public java.util.List<HiddenAttachmentBlock> findHiddenAttachmentBlocks(Post post) {
        java.util.LinkedHashMap<String, HiddenAttachmentBlock> blocks = new java.util.LinkedHashMap<>();
        com.biliwind.blog.model.PostRevision revision = resolvePublicContentRevision(post);
        if (revision == null || revision.contentMarkdown == null) {
            return java.util.List.of();
        }
        for (String content : revision.contentMarkdown.values()) {
            for (HiddenAttachmentBlock block : parseHiddenAttachmentBlocks(content)) {
                blocks.putIfAbsent(block.blockId(), block);
            }
        }
        return new java.util.ArrayList<>(blocks.values());
    }

    /** Attachment blocks whose body references the given media. */
    public java.util.List<HiddenAttachmentBlock> findHiddenAttachmentBlocksForMedia(
            Post post, com.biliwind.blog.model.Media media) {
        if (post == null || media == null) {
            return java.util.List.of();
        }
        java.util.List<String> tokens = attachmentMediaTokens(media);
        if (tokens.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<HiddenAttachmentBlock> matches = new java.util.ArrayList<>();
        for (HiddenAttachmentBlock block : findHiddenAttachmentBlocks(post)) {
            if (block.content() == null) {
                continue;
            }
            for (String token : tokens) {
                if (block.content().contains(token)) {
                    matches.add(block);
                    break;
                }
            }
        }
        return matches;
    }

    public boolean isMediaInsideHiddenAttachment(Post post, com.biliwind.blog.model.Media media) {
        return !findHiddenAttachmentBlocksForMedia(post, media).isEmpty();
    }

    /**
     * Free attachments are visible to any logged-in user. Priced attachments
     * need the matching block purchase, or the whole article purchase.
     */
    public boolean canAccessHiddenAttachment(Post post, HiddenAttachmentBlock block, Long userId) {
        if (post == null || block == null || userId == null) {
            return false;
        }
        if (post.user != null && userId.equals(post.user.id)) {
            return true;
        }
        if (hasPurchasedPost(userId, post.id)) {
            return true;
        }
        if (block.price() <= 0) {
            return true;
        }
        return hasPurchasedBlock(userId, post.id, block.blockId());
    }

    public boolean canAccessProtectedMedia(Post post, com.biliwind.blog.model.Media media, Long userId) {
        if (post == null || media == null || userId == null) {
            return false;
        }
        if (post.user != null && userId.equals(post.user.id)) {
            return true;
        }
        if (hasPurchasedPost(userId, post.id)) {
            return true;
        }
        for (HiddenAttachmentBlock block : findHiddenAttachmentBlocksForMedia(post, media)) {
            if (canAccessHiddenAttachment(post, block, userId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the right-sidebar attachment list for the current reader. Locked
     * attachments never expose their URL; free ones show a login prompt and
     * priced ones show the block purchase action.
     */
    public java.util.List<AttachmentItem> resolveAttachmentItems(Post post, String content,
                                                                 Long userId, String deviceId) {
        java.util.List<AttachmentItem> items = new java.util.ArrayList<>();
        if (post == null || content == null || content.isEmpty()) {
            return items;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (HiddenAttachmentBlock block : parseHiddenAttachmentBlocks(content)) {
            if (!seen.add(block.blockId())) {
                continue;
            }
            boolean unlocked = canAccessHiddenAttachment(post, block, userId);
            boolean loginRequired = !unlocked && block.price() <= 0;
            String downloadUrl = null;
            String formattedSize = "";
            if (unlocked && userId != null) {
                com.biliwind.blog.model.Media media = findMediaByAttachmentUrl(block.url());
                if (media != null && media.deletedAt == null) {
                    formattedSize = formatFileSize(media.size);
                    downloadUrl = "/api/media/download/" + contentAccessTicketService
                            .issueMediaDownloadPath(media.id, post.id, userId,
                                    java.time.Duration.ofMinutes(10), deviceId);
                } else if (block.url() != null && !block.url().startsWith("/uploads/")) {
                    downloadUrl = block.url();
                }
            }
            items.add(new AttachmentItem(block.blockId(), block.name(), block.price(), unlocked,
                    loginRequired, downloadUrl, formattedSize));
        }
        return items;
    }

    /**
     * Wraps unlocked block content in the same amber shell used by the Flutter
     * editor preview. The shell is injected as an HTML block; blank lines keep
     * the inner Markdown parseable after the shell header (CommonMark rule).
     */
    private String renderUnlockedBlock(String blockType, String blockId, String attrStr, String content) {
        boolean attachment = "hide-attachment".equals(blockType);
        String show = extractAttribute(attrStr, "show");
        long price = 0L;
        String priceText = extractAttribute(attrStr, "price");
        if (priceText != null && !priceText.isBlank()) {
            try {
                price = Long.parseLong(priceText.trim());
            } catch (NumberFormatException ignored) {
                price = 0L;
            }
        }
        price = Math.max(0L, price);

        String title;
        if (show != null && !show.isBlank()) {
            title = show.trim();
        } else if (attachment) {
            title = translateOrDefault("post.hide_block.attachment_default", "付费附件区块");
        } else {
            title = translateOrDefault("post.hide_block.text_default", "付费隐藏内容");
        }
        String priceLabel = null;
        if (price > 0) {
            priceLabel = translatePrice(price);
        }
        return "\n\n" + hideBlockTemplate
                .data("blockType", blockType)
                .data("blockId", blockId)
                .data("icon", attachment ? "📎" : "🔓")
                .data("title", title)
                .data("priceLabel", priceLabel)
                .data("statusLabel", translateOrDefault("post.hide_block.unlocked", "已解锁"))
                .data("content", content == null ? "" : content)
                .render() + "\n\n";
    }

    /**
     * Snapshot previews may be rebuilt outside a request; fall back to the
     * default-language labels instead of failing on the request-scoped i18n bean.
     */
    private String translateOrDefault(String key, String fallback) {
        try {
            String value = i18n.t(key);
            return value == null || value.isBlank() || value.equals(key) ? fallback : value;
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String translatePrice(long price) {
        try {
            return i18n.format("post.hide_block.price", price);
        } catch (Exception exception) {
            return price + " 积分";
        }
    }

    /** Issues a short-lived download path for an attachment after access was checked. */
    public String issueAttachmentDownloadUrl(Long mediaId, Long postId, Long userId, String deviceId) {
        if (mediaId == null || postId == null || userId == null) {
            return null;
        }
        return "/api/media/download/" + contentAccessTicketService.issueMediaDownloadPath(
                mediaId, postId, userId, java.time.Duration.ofMinutes(10), deviceId);
    }

    private java.util.List<HiddenAttachmentBlock> parseHiddenAttachmentBlocks(String content) {
        java.util.List<HiddenAttachmentBlock> blocks = new java.util.ArrayList<>();
        if (content == null || content.isEmpty()) {
            return blocks;
        }
        java.util.regex.Matcher matcher = HIDDEN_ATTACHMENT_PATTERN.matcher(content);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String body = matcher.group(2);
            String explicitId = extractAttribute(attributes, "id");
            String blockId = (explicitId != null && !explicitId.isBlank())
                    ? explicitId : generateBlockId(attributes, body);
            long price = 0L;
            String priceText = extractAttribute(attributes, "price");
            if (priceText != null && !priceText.isBlank()) {
                try {
                    price = Long.parseLong(priceText.trim());
                } catch (NumberFormatException ignored) {
                    price = 0L;
                }
            }
            String url = extractAttachmentUrl(body);
            String name = extractAttribute(attributes, "show");
            if (name == null || name.isBlank()) {
                name = extractAttachmentLabel(body);
            }
            if (name == null || name.isBlank()) {
                name = attachmentFileName(url);
            }
            if (name == null || name.isBlank()) {
                name = "附件";
            }
            blocks.add(new HiddenAttachmentBlock(blockId, name.trim(), Math.max(0L, price), body, url));
        }
        return blocks;
    }

    private java.util.List<String> attachmentMediaTokens(com.biliwind.blog.model.Media media) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        addAttachmentToken(tokens, media.url);
        addAttachmentToken(tokens, media.storageKey);
        if (media.storageKey != null && !media.storageKey.isBlank()) {
            addAttachmentToken(tokens, "/uploads/" + media.storageKey);
        }
        addAttachmentToken(tokens, media.fileName);
        return tokens;
    }

    private void addAttachmentToken(java.util.List<String> tokens, String value) {
        if (value != null && !value.isBlank() && value.length() >= 4 && !tokens.contains(value)) {
            tokens.add(value);
        }
    }

    private String extractAttachmentUrl(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.regex.Matcher markdown = MARKDOWN_ATTACHMENT_LINK.matcher(body);
        while (markdown.find()) {
            String candidate = sanitizeAttachmentUrl(markdown.group(2));
            if (candidate != null) {
                return candidate;
            }
        }
        java.util.regex.Matcher html = HTML_ATTACHMENT_LINK.matcher(body);
        while (html.find()) {
            String candidate = sanitizeAttachmentUrl(html.group(1));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractAttachmentLabel(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.regex.Matcher markdown = MARKDOWN_ATTACHMENT_LINK.matcher(body);
        while (markdown.find()) {
            String label = markdown.group(1);
            if (label != null && !label.isBlank()) {
                return label.trim();
            }
        }
        java.util.regex.Matcher html = HTML_ATTACHMENT_LINK.matcher(body);
        while (html.find()) {
            String label = html.group(2) == null ? null : html.group(2).replaceAll("<[^>]+>", "").trim();
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return null;
    }

    private String sanitizeAttachmentUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return null;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
            return null;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://") || url.startsWith("/")) {
            return url;
        }
        return null;
    }

    private String attachmentFileName(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url.trim();
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        int fragmentIndex = path.indexOf('#');
        if (fragmentIndex >= 0) {
            path = path.substring(0, fragmentIndex);
        }
        int slashIndex = path.lastIndexOf('/');
        String name = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
        try {
            return java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return name;
        }
    }

    private com.biliwind.blog.model.Media findMediaByAttachmentUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String candidate = url.trim();
        com.biliwind.blog.model.Media media = com.biliwind.blog.model.Media
                .find("url = ?1 and deletedAt is null", candidate).firstResult();
        if (media != null) {
            return media;
        }
        int queryIndex = candidate.indexOf('?');
        if (queryIndex >= 0) {
            candidate = candidate.substring(0, queryIndex);
        }
        int fragmentIndex = candidate.indexOf('#');
        if (fragmentIndex >= 0) {
            candidate = candidate.substring(0, fragmentIndex);
        }
        String path = candidate;
        if (!path.startsWith("/uploads/")) {
            try {
                java.net.URI uri = java.net.URI.create(candidate);
                path = uri.getPath() == null ? "" : uri.getPath();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!path.startsWith("/uploads/")) {
            return null;
        }
        String storageKey = path.substring("/uploads/".length());
        if (storageKey.isBlank() || storageKey.contains("/")) {
            return null;
        }
        return com.biliwind.blog.model.Media
                .find("storageKey = ?1 and deletedAt is null", storageKey).firstResult();
    }

    private String formatFileSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public boolean hasProtectedMediaReference(Long mediaId) {
        if (mediaId == null) {
            return false;
        }
        java.util.List<PostMedia> references = PostMedia.find(
                "select relation from PostMedia relation join fetch relation.post "
                        + "where relation.media.id = ?1", mediaId).list();
        for (PostMedia reference : references) {
            if (isProtectedMediaReference(reference)) {
                return true;
            }
        }
        return false;
    }

    public java.util.List<PostMedia> findProtectedMediaReferences(Long postId) {
        if (postId == null) {
            return java.util.List.of();
        }
        java.util.List<PostMedia> references = PostMedia.find(
                "select relation from PostMedia relation join fetch relation.post "
                        + "where relation.post.id = ?1", postId).list();
        java.util.List<PostMedia> protectedReferences = new java.util.ArrayList<>();
        for (PostMedia reference : references) {
            if (isProtectedMediaReference(reference)) {
                protectedReferences.add(reference);
            }
        }
        return protectedReferences;
    }

    public boolean verifyPassword(Post post, String submittedPassword) {
        if (post == null || submittedPassword == null || submittedPassword.isBlank()
                || post.password == null || post.password.isBlank()) {
            return false;
        }
        return passwordHasher.matches(submittedPassword, post.password);
    }

    /**
     * 判断用户是否已购买此文章的特定区块
     */
    public boolean hasPurchasedBlock(Long userId, Long postId, String blockId) {
        if (userId == null || blockId == null) return false;
        return UserPurchaseRecord.count("userId = ?1 and targetType = 'POST' and targetId = ?2 and targetBlockId = ?3", userId, postId, blockId) > 0;
    }

    /**
     * 判断用户是否已购买此文章
     */
    public boolean hasPurchasedPost(Long userId, Long postId) {
        if (userId == null) return false;
        return UserPurchaseRecord.count("userId = ?1 and targetType = 'POST' and targetId = ?2 and targetBlockId is null", userId, postId) > 0;
    }

    /** Reject reposting while any paid article content remains locked. */
    public boolean hasUnlockedAllPaidContent(Post post, Long userId) {
        if (post == null) {
            return false;
        }
        if (post.user != null && userId != null && userId.equals(post.user.id)) {
            return true;
        }
        if (hasPurchasedPost(userId, post.id)) {
            return true;
        }
        Long fullArticlePrice = getExtraPointsPrice(post);
        if (fullArticlePrice != null && fullArticlePrice > 0) {
            return false;
        }

        com.biliwind.blog.model.PostRevision revision = resolvePublicContentRevision(post);
        if (revision == null || revision.contentMarkdown == null) {
            return true;
        }
        java.util.regex.Pattern blockPattern = java.util.regex.Pattern.compile(
                "\\[\\s*(hide-text|hide-attachment)(.*?)\\](.*?)\\[\\s*/\\1\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String content : revision.contentMarkdown.values()) {
            if (content == null) continue;
            java.util.regex.Matcher matcher = blockPattern.matcher(content);
            while (matcher.find()) {
                String attributes = matcher.group(2);
                long price = parseBlockPrice(attributes);
                if (price <= 0) continue;
                String explicitId = extractAttribute(attributes, "id");
                String blockId = explicitId == null || explicitId.isBlank()
                        ? generateBlockId(attributes, matcher.group(3)) : explicitId;
                if (!hasPurchasedBlock(userId, post.id, blockId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 获取用户为该文章支付过的最高积分（用于阶梯解锁）
     */
    public long getMaxPointsPaid(Long userId, Long postId) {
        if (userId == null) return -1;
        Long max = UserPurchaseRecord.find("SELECT MAX(pointsPaid) FROM UserPurchaseRecord WHERE userId = ?1 AND targetType = 'POST' AND targetId = ?2", userId, postId)
                .project(Long.class).firstResult();
        return max != null ? max : -1;
    }

    /**
     * 获取文章的买断价格。
     * 优先级：extraInfo.points_price > 短代码中价格的总和 > 0
     */
    public long getPostPrice(Post post) {
        // 1. 检查元数据中的全局价格
        Long extraPrice = getExtraPointsPrice(post);
        if (extraPrice != null) return extraPrice;

        // 2. 检查内容中的短代码价格总和
        com.biliwind.blog.model.PostRevision contentRevision = resolvePublicContentRevision(post);
        if (contentRevision != null && contentRevision.contentMarkdown != null) {
            long maxPrice = 0;
            // 匹配包含 hide-text 或 hide-attachment 的标签及其内部所有属性
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\\[\\s*(?:hide-text|hide-attachment)(.*?)\\]",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

            // 遍历所有语言版本，取价格总和最大的那个版本作为文章价格（避免多语言累加）
            for (String content : contentRevision.contentMarkdown.values()) {
                if (content != null) {
                    long currentLangTotal = 0;
                    java.util.regex.Matcher m = p.matcher(content);
                    while (m.find()) {
                        String attrStr = m.group(1);
                        String priceStr = extractAttribute(attrStr, "price");
                        if (priceStr != null) {
                            try {
                                currentLangTotal += Long.parseLong(priceStr);
                            } catch (Exception e) {
                                // 忽略解析失败
                            }
                        }
                    }
                    if (currentLangTotal > maxPrice) {
                        maxPrice = currentLangTotal;
                    }
                }
            }
            if (maxPrice > 0) return maxPrice;
        }

        return 0;
    }

    /**
     * 购买文章买断或特定区块
     * 服务端会根据文章/区块实际价格校验前端传入的 points，防止价格篡改。
     */
    @jakarta.transaction.Transactional
    public void buyPost(Long userId, Long postId, Long points, String blockId) {
        if (userId == null) throw new BadRequestException("必须登录后才能购买");

        Post post = Post.findById(postId);
        if (post == null) throw new BadRequestException("文章不存在");

        if (blockId != null) {
            if (hasPurchasedBlock(userId, postId, blockId)) {
                throw new BadRequestException("您已经解锁过此区块");
            }
        } else {
            if (hasPurchasedPost(userId, postId)) {
                throw new BadRequestException("您已经购买过此文章");
            }
        }

        long postPrice = getPostPrice(post);

        // 如果已经全站买断，则无需再买区块（依赖明确的全文购买记录，不用积分比较）
        if (hasPurchasedPost(userId, postId)) {
            return;
        }

        long priceToPay;
        if (blockId != null) {
            priceToPay = resolveBlockPrice(post, blockId);
            if (points != null && points.longValue() != priceToPay) {
                throw new BadRequestException("价格确认失败，请刷新后重试");
            }
        } else {
            priceToPay = postPrice;
        }

        // 扣除积分（如果价格大于0）
        if (priceToPay > 0) {
            WalletService walletService = jakarta.enterprise.inject.spi.CDI.current().select(WalletService.class).get();
            String desc = (blockId != null) ? ("解锁文章区块: " + post.slug) : ("解锁全站文章内容: " + post.slug);
            walletService.deductPoints(userId, priceToPay, "BUY_POST", postId, desc);
        }

        // 记录购买流水
        UserPurchaseRecord record = new UserPurchaseRecord();
        record.userId = userId;
        record.targetType = "POST";
        record.targetId = postId;
        record.targetBlockId = blockId;
        record.pointsPaid = priceToPay;
        record.persist();
    }

    public long resolveBlockPrice(Post post, String blockId) {
        if (post == null) {
            throw new BadRequestException("文章不存在");
        }
        if (blockId == null || blockId.isBlank()) {
            throw new BadRequestException("区块不存在");
        }
        com.biliwind.blog.model.PostRevision contentRevision = resolvePublicContentRevision(post);
        if (contentRevision == null || contentRevision.contentMarkdown == null) {
            throw new BadRequestException("区块不存在");
        }

        for (String content : contentRevision.contentMarkdown.values()) {
            Long blockPrice = findBlockPriceInContent(content, blockId);
            if (blockPrice != null) {
                if (blockPrice < 0) {
                    throw new BadRequestException("价格不能为负数");
                }
                return blockPrice;
            }
        }

        throw new BadRequestException("区块不存在");
    }

    private Long findBlockPriceInContent(String content, String targetBlockId) {
        if (content == null || content.isBlank()) {
            return null;
        }

        java.util.regex.Pattern blockPattern = java.util.regex.Pattern.compile(
                "\\[\\s*(hide-text|hide-attachment)(.*?)\\](.*?)\\[\\s*/\\1\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher blockMatcher = blockPattern.matcher(content);
        while (blockMatcher.find()) {
            String attrText = blockMatcher.group(2);
            String blockContent = blockMatcher.group(3);
            String explicitBlockId = extractAttribute(attrText, "id");
            String currentBlockId = explicitBlockId;
            if (currentBlockId == null || currentBlockId.isBlank()) {
                currentBlockId = generateBlockId(attrText, blockContent);
            }
            if (targetBlockId.equals(currentBlockId)) {
                return parseBlockPrice(attrText);
            }
        }

        return null;
    }

    private long parseBlockPrice(String attrText) {
        String priceText = extractAttribute(attrText, "price");
        if (priceText == null || priceText.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(priceText);
        } catch (NumberFormatException e) {
            throw new BadRequestException("区块价格不合法");
        }
    }

    /**
     * 根据购买状态过滤文章内容中的隐藏短代码，并替换为占位符提示。
     * 支持 [hide-text price=100]...[/hide-text]
     * 支持 [hide-attachment price=200]...[/hide-attachment]
     *
     * @param rawContent    原始 Markdown 内容
     * @param maxPointsPaid 用户已支付的最高积分
     * @param isAuthor      是否为文章作者
     * @param postId        文章 ID
     * @param postPrice     文章价格
     * @param userId        用户 ID
     * @return 处理后的内容
     */
    public String filterHiddenContent(String rawContent, long maxPointsPaid, boolean isAuthor, Long postId, long postPrice, Long userId) {
        return filterHiddenContent(rawContent, maxPointsPaid, isAuthor, postId, postPrice, userId,
                com.biliwind.blog.model.PostRenderType.MARKDOWN);
    }

    /**
     * 根据购买状态过滤文章内容中的隐藏短代码，并渲染为最终 HTML。
     * 支持 [hide-text price=100]...[/hide-text]
     * 支持 [hide-attachment price=200]...[/hide-attachment]
     *
     * @param rawContent    原始 Markdown 内容
     * @param maxPointsPaid 用户已支付的最高积分
     * @param isAuthor      是否为文章作者
     * @param postId        文章 ID
     * @param postPrice     文章价格
     * @param userId        用户 ID
     * @return 处理后的内容
     */
    public String filterHiddenContent(String rawContent, long maxPointsPaid, boolean isAuthor, Long postId,
                                      long postPrice, Long userId, com.biliwind.blog.model.PostRenderType renderType) {
        if (rawContent == null || rawContent.isEmpty()) return rawContent;
        String postRef = resolvePostRef(postId);

        // 全站买断判定：只依赖作者身份或明确的全文购买记录，不用积分比较
        boolean fullUnlocked = isAuthor
                || hasPurchasedPost(userId, postId);

        String html = renderHiddenBlocks(rawContent, renderType, (blockType, attrStr, body, effectiveRenderType) -> {
            boolean attachment = "hide-attachment".equals(blockType);
            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, body);

            String priceStr = extractAttribute(attrStr, "price");
            long requiredPrice = (priceStr != null && !priceStr.isEmpty()) ? Long.parseLong(priceStr) : 0;

            // 解锁逻辑：或者是全站买断（fullUnlocked），或者已经购买过这个特定的 blockId，或者是免费区块
            boolean isUnlocked = attachment
                    ? fullUnlocked || hasPurchasedBlock(userId, postId, blockId)
                            || (requiredPrice == 0 && userId != null)
                    : fullUnlocked || (requiredPrice == 0) || hasPurchasedBlock(userId, postId, blockId);

            if (isUnlocked) {
                // 已解锁：保留 Flutter 预览中的区块外壳
                return renderUnlockedBlock(blockType, blockId, attrStr,
                        renderAuthoredMarkup(body, effectiveRenderType));
            }

            if (attachment) {
                // 未解锁：显示占位符
                boolean requiresLogin = requiredPrice == 0;
                String showName = extractAttribute(attrStr, "show");
                String label;
                if (showName != null && !showName.isBlank()) {
                    label = showName.trim() + (requiredPrice > 0 ? " (" + requiredPrice + " 积分)" : "");
                } else {
                    label = (requiredPrice > 0 ? "专享资源 (" + requiredPrice + " 积分)" : "专享资源");
                }
                String displayPrice = String.valueOf(requiredPrice > 0 ? requiredPrice : postPrice);
                String buttonText = requiresLogin ? "登录后查看附件"
                        : ("支付 " + displayPrice + " 积分解锁附件");
                String descText = requiresLogin
                        ? "该附件仅登录用户可查看，登录后即可获取下载链接"
                        : "解锁该资源即可获取下载链接，或购买全文解锁更多";

                return "\n\n" + premiumAttachmentTemplate
                        .data("title", label)
                        .data("desc", descText)
                        .data("showButton", true)
                        .data("requiresLogin", requiresLogin)
                        .data("postRef", postRef)
                        .data("price", displayPrice)
                        .data("blockId", blockId)
                        .data("buttonText", buttonText)
                        .render() + "\n\n";
            }

            // 未解锁：显示占位符
            String title = extractAttribute(attrStr, "show");
            if (title == null) title = "专享内容已锁定";

            boolean isFree = requiredPrice == 0;
            String displayPrice = String.valueOf(requiredPrice > 0 ? requiredPrice : postPrice);
            String buttonText = isFree ? "免费解锁" : ("支付 " + displayPrice + " 积分解锁区块");
            String descText = isFree ? "当前为免费专享区块，解锁后即可阅读隐藏内容" : "解锁该区块即可查看精彩内容，或购买全文解锁更多";

            return "\n\n" + premiumCardTemplate
                    .data("fullHide", false)
                    .data("title", title)
                    .data("desc", descText)
                    .data("showButton", true)
                    .data("postRef", postRef)
                    .data("price", displayPrice)
                    .data("blockId", blockId)
                    .data("buttonText", buttonText)
                    .render() + "\n\n";
        });

        return html;
    }

    /**
     * 获取用户在当前文章中已解锁的区块内容字典。
     */
    public java.util.Map<String, String> getUnlockedBlocks(String rawContent, long maxPointsPaid, boolean isAuthor, Long postId, long postPrice, Long userId) {
        java.util.Map<String, String> unlockedBlocks = new java.util.HashMap<>();
        if (rawContent == null || rawContent.isEmpty()) return unlockedBlocks;
        String postRef = resolvePostRef(postId);

        // 全站买断判定：只依赖作者身份或明确的全文购买记录，不用积分比较
        boolean fullUnlocked = isAuthor
                || hasPurchasedPost(userId, postId);

        // 处理 [hide-text]
        java.util.regex.Pattern textPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-text(.*?)\\](.*?)\\[\\s*/hide-text\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher textMatcher = textPattern.matcher(rawContent);
        while (textMatcher.find()) {
            String attrStr = textMatcher.group(1);
            String content = textMatcher.group(2);
            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, content);
            String priceStr = extractAttribute(attrStr, "price");
            long requiredPrice = 0;
            if (priceStr != null && !priceStr.isEmpty()) {
                try {
                    requiredPrice = Long.parseLong(priceStr);
                } catch (Exception exception) {
                    // 忽略解析错误，默认为 0
                }
            }
            if (fullUnlocked || (requiredPrice == 0) || hasPurchasedBlock(userId, postId, blockId)) {
                unlockedBlocks.put(blockId, renderUnlockedBlock("hide-text", blockId, attrStr, content));
            }
        }

        // 处理 [hide-attachment]
        java.util.regex.Pattern attachPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-attachment(.*?)\\](.*?)\\[\\s*/hide-attachment\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher attachMatcher = attachPattern.matcher(rawContent);
        while (attachMatcher.find()) {
            String attrStr = attachMatcher.group(1);
            String content = attachMatcher.group(2);
            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, content);
            String priceStr = extractAttribute(attrStr, "price");
            long requiredPrice = 0;
            if (priceStr != null && !priceStr.isEmpty()) {
                try {
                    requiredPrice = Long.parseLong(priceStr);
                } catch (Exception exception) {
                    // 忽略解析错误，默认为 0
                }
            }
            if (fullUnlocked
                    || hasPurchasedBlock(userId, postId, blockId)
                    || (requiredPrice == 0 && userId != null)) {
                unlockedBlocks.put(blockId, renderUnlockedBlock("hide-attachment", blockId, attrStr, content));
            }
        }

        return unlockedBlocks;
    }

    // 处理 [store-item id=XXX]
    public String processStoreItems(String filtered) {
        java.util.regex.Pattern storePattern = java.util.regex.Pattern.compile(
                "\\[\\s*store-item\\s+id\\s*=\\s*(?:\"([^\"]*)\"|([^\\s\\]]+))\\s*\\]",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher storeMatcher = storePattern.matcher(filtered);
        StringBuilder sb = new StringBuilder();
        while (storeMatcher.find()) {
            String idStr = storeMatcher.group(1) != null ? storeMatcher.group(1) : storeMatcher.group(2);
            try {
                Long itemId = Long.parseLong(idStr);
                com.biliwind.blog.model.StoreItem item = com.biliwind.blog.model.StoreItem.findById(itemId);
                if (item != null) {
                    String itemHtml = "\n\n" + storeItemCardTemplate
                            .data("name", item.name)
                            .data("price", item.price)
                            .data("desc", item.description != null ? item.description : "暂无描述")
                            .render() + "\n\n";
                    storeMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(itemHtml));
                } else {
                    storeMatcher.appendReplacement(sb, "<!-- Store Item " + itemId + " Not Found -->");
                }
            } catch (Exception e) {
                storeMatcher.appendReplacement(sb, "<!-- Invalid Store Item ID: " + idStr + " -->");
            }
        }
        storeMatcher.appendTail(sb);
        filtered = sb.toString();

        // 统一处理可能存在的旧版 [hide-image] 或 [attachment] (兼容性)
        filtered = filtered.replaceAll("\\[hide-image url=\"([^\"]+)\"\\]", "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div><div class=\"md-region-content\"><strong>付费图片已隐藏</strong>：购买文章后解锁。</div></div>\n\n");
        filtered = filtered.replaceAll("\\[attachment id=\"([^\"]+)\"\\]", "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div><div class=\"md-region-content\"><strong>专属附件已隐藏</strong>：购买文章后解锁。</div></div>\n\n");

        return filtered;
    }


    private String generateBlockId(String attrStr, String content) {
        // 只使用属性字符串（price、show 等）计算哈希，不包含正文内容。
        // 这样作者修改区块内容文字时不会导致 blockId 变化，已购记录不失效。
        // 根本解决方案是在标签上加显式 id= 属性。
        try {
            String inputForHash = (attrStr != null ? attrStr.trim() : "");
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(inputForHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            return "block-" + (attrStr != null ? attrStr.hashCode() : "unknown");
        }
    }

    public Long getExtraPointsPrice(Post post) {
        return com.biliwind.blog.common.helper.PostHelper.getExtraPointsPrice(post);
    }

    public int getFreeLines(Post post) {
        return com.biliwind.blog.common.helper.PostHelper.getFreeLines(post);
    }

    /**
     * 获取带缓存的静态预览内容。使用 Redis 缓存，防止每次访问都进行正则替换。
     * 只有在管理员后台更新文章产生新的 revision 时才会自然失效（因为 revision id 变了）。
     */
    public String getCachedPreviewContent(Post post, String lang, String localizedContent, long postPrice) {
        String revisionStr = post.publishedRevision != null ? String.valueOf(post.publishedRevision.id) : "0";
        java.util.Optional<PublicCacheRefreshService.PublicPostSnapshot> publicSnapshot =
                cacheService.get(com.biliwind.blog.common.CacheService.Keys.postMeta(post.slug),
                        PublicCacheRefreshService.PublicPostSnapshot.class);
        if (publicSnapshot.isPresent()) {
            PublicCacheRefreshService.PublicPostSnapshot snapshot = publicSnapshot.get();
            if (snapshot.publishedRevisionId() != null
                    && snapshot.publishedRevisionId().equals(post.publishedRevision == null ? null : post.publishedRevision.id)
                    && snapshot.previewContent() != null) {
                String snapshotContent = snapshot.previewContent().get(lang);
                if (snapshotContent != null) {
                    return snapshotContent;
                }
            }
        }
        String cacheKey = "post:preview:v3:" + post.id + ":" + lang + ":" + revisionStr;

        java.util.Optional<String> cached = cacheService.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        int freeLines = getFreeLines(post);
        String previewContent = getPreviewOnlyContent(localizedContent, freeLines, postPrice > 0,
                post.id, postPrice, null, post.renderType);

        cacheService.set(cacheKey, previewContent, java.time.Duration.ofDays(7));
        return previewContent;
    }

    public String getPreviewOnlyContent(String content, int freeLines, boolean isPaid, Long postId, long postPrice, Long userId) {
        return getPreviewOnlyContent(content, freeLines, isPaid, postId, postPrice, userId,
                com.biliwind.blog.model.PostRenderType.MARKDOWN);
    }

    /**
     * 获取仅包含免费预览内容的版本（用于初始页面加载）。
     * 返回已净化的最终 HTML，模板按钮不会再被下游净化器移除。
     */
    public String getPreviewOnlyContent(String content, int freeLines, boolean isPaid, Long postId, long postPrice,
                                        Long userId, com.biliwind.blog.model.PostRenderType renderType) {
        if (content == null) return null;
        // 预览模式下假设未支付任何积分
        if (!isPaid) {
            String filteredFreeContent = filterHiddenContent(content, -1, false, postId, postPrice, userId, renderType);
            return removeProtectedMediaReferences(postId, filteredFreeContent);
        }

        String preview;
        if (freeLines > 0 && freeLines >= content.split("\r?\n", -1).length) {
            preview = filterHiddenContentForPreview(content, postId, postPrice, userId, renderType);
        } else {
            String truncated = truncateRawForFreePreview(content, freeLines);
            preview = filterHiddenContentForPreview(truncated, postId, postPrice, userId, renderType)
                    + renderFullLockCard(postId, postPrice, freeLines);
        }
        return removeProtectedMediaReferences(postId, preview);
    }

    public String rewriteProtectedMediaReferences(Long postId, String content, Long userId,
                                                   boolean authorized, String deviceId) {
        Post post = postId == null ? null : Post.findById(postId);
        return rewriteProtectedMediaReferences(post, content, userId, authorized, deviceId);
    }

    public String rewriteProtectedMediaReferences(Post post, String content, Long userId,
                                                   boolean authorized, String deviceId) {
        if (content == null || content.isBlank() || post == null) {
            return content;
        }
        Long postId = post.id;
        java.util.List<PostMedia> references = findProtectedMediaReferences(postId);
        String rewritten = content;
        java.util.Map<Long, String> replacementByMedia = new java.util.HashMap<>();
        for (PostMedia reference : references) {
            if (reference.media == null || reference.media.deletedAt != null) {
                continue;
            }
            String replacement = replacementByMedia.get(reference.media.id);
            for (String source : protectedMediaSources(reference.media)) {
                if (!rewritten.contains(source)) {
                    continue;
                }
                if (replacement == null) {
                    // A login is enough for a free attachment; priced blocks and
                    // protected posts still need their own purchase/author check.
                    boolean canDownload = authorized
                            || canAccessProtectedMedia(post, reference.media, userId);
                    if (!canDownload || userId == null) {
                        replacement = "[受保护附件已隐藏]";
                    } else {
                        replacement = "/api/media/download/"
                                + contentAccessTicketService.issueMediaDownloadPath(
                                reference.media.id, postId, userId,
                                java.time.Duration.ofMinutes(10), deviceId);
                    }
                    replacementByMedia.put(reference.media.id, replacement);
                }
                rewritten = rewritten.replace(source, replacement);
            }
        }
        return rewritten;
    }

    private String removeProtectedMediaReferences(Long postId, String content) {
        return rewriteProtectedMediaReferences(postId, content, null, false, null);
    }

    private java.util.List<String> protectedMediaSources(com.biliwind.blog.model.Media media) {
        java.util.Set<String> sources = new java.util.LinkedHashSet<>();
        addMediaSource(sources, media.url);
        addMediaSource(sources, media.storageKey);
        if (media.metadata != null) {
            for (String key : java.util.List.of("thumbnailUrl", "previewUrl", "webpUrl",
                    "placeholderUrl", "coverUrl", "importPlaceholderUrl")) {
                Object value = media.metadata.get(key);
                if (value != null) {
                    addMediaSource(sources, value.toString());
                }
            }
        }
        collectStoragePaths(sources, media.storageClasses);
        return new java.util.ArrayList<>(sources);
    }

    private void collectStoragePaths(java.util.Set<String> sources, Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if ("path".equals(entry.getKey()) && entry.getValue() != null) {
                    addMediaSource(sources, entry.getValue().toString());
                } else {
                    collectStoragePaths(sources, entry.getValue());
                }
            }
        } else if (value instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                collectStoragePaths(sources, item);
            }
        }
    }

    private void addMediaSource(java.util.Set<String> sources, String value) {
        if (value != null && !value.isBlank() && value.length() >= 4) {
            sources.add(value);
        }
    }

    /**
     * 为预览版过滤隐藏内容：所有受保护区块都渲染为锁定占位符。
     */
    private String filterHiddenContentForPreview(String rawContent, Long postId, long postPrice, Long userId,
                                                 com.biliwind.blog.model.PostRenderType renderType) {
        if (rawContent == null || rawContent.isEmpty()) {
            return "";
        }
        String postRef = resolvePostRef(postId);

        String html = renderHiddenBlocks(rawContent, renderType, (blockType, attrStr, body, effectiveRenderType) -> {
            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, body);
            String priceAttr = extractAttribute(attrStr, "price");
            boolean isFree = priceAttr == null || priceAttr.equals("0") || priceAttr.isEmpty();
            String displayPrice = isFree ? String.valueOf(postPrice) : priceAttr;

            if ("hide-attachment".equals(blockType)) {
                String buttonText = isFree ? "登录后查看附件"
                        : ("支付 " + displayPrice + " 积分解锁附件");
                String showName = extractAttribute(attrStr, "show");
                String title = (showName == null || showName.isBlank()) ? "专属附件已隐藏" : showName.trim();
                String descText = isFree
                        ? "该附件仅登录用户可查看，登录后即可获取下载链接"
                        : "您可以解锁当前区块或购买整篇文章后查看该专属附件。";

                return "\n\n" + premiumAttachmentTemplate
                        .data("title", title)
                        .data("desc", descText)
                        .data("showButton", true)
                        .data("requiresLogin", isFree)
                        .data("postRef", postRef)
                        .data("price", displayPrice)
                        .data("blockId", blockId)
                        .data("buttonText", buttonText)
                        .render() + "\n\n";
            }

            String show = extractAttribute(attrStr, "show");
            String title = (show != null) ? show : "专享内容已锁定";
            String buttonText = isFree ? "免费解锁" : ("支付 " + displayPrice + " 积分解锁区块");
            String descText = isFree ? "当前为免费专享区块，解锁后即可阅读隐藏内容" : "解锁该区块即可查看精彩内容，或购买全文解锁更多";

            return "\n\n" + premiumCardTemplate
                    .data("fullHide", false)
                    .data("title", title)
                    .data("desc", descText)
                    .data("showButton", true)
                    .data("postRef", postRef)
                    .data("price", displayPrice)
                    .data("blockId", blockId)
                    .data("buttonText", buttonText)
                    .render() + "\n\n";
        });

        return html;
    }

    /**
     * 为付费文章预览截断正文：按原始 Markdown 行数保留免费部分，避免在渲染后的
     * HTML 中按行截断而切坏标签或模板按钮。
     */
    private String truncateRawForFreePreview(String content, int freeLines) {
        if (content == null || content.isEmpty() || freeLines <= 0) {
            return "";
        }
        String[] lines = content.split("\r?\n", -1);
        if (lines.length <= freeLines) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < freeLines; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return dropUnterminatedHiddenBlock(sb.toString());
    }

    private String dropUnterminatedHiddenBlock(String prefix) {
        java.util.regex.Matcher matcher = HIDDEN_BLOCK_OPEN_PATTERN.matcher(prefix);
        int lastUnterminated = -1;
        while (matcher.find()) {
            String close = "[/" + matcher.group(1) + "]";
            if (prefix.toLowerCase(java.util.Locale.ROOT)
                    .indexOf(close.toLowerCase(java.util.Locale.ROOT), matcher.end()) < 0) {
                lastUnterminated = matcher.start();
            }
        }
        return lastUnterminated >= 0 ? prefix.substring(0, lastUnterminated) : prefix;
    }

    private String renderFullLockCard(Long postId, long postPrice, int freeLines) {
        boolean fullHide = freeLines <= 0;
        return "\n\n" + premiumCardTemplate
                .data("fullHide", fullHide)
                .data("title", fullHide ? "本文内容已锁定" : "专享内容已锁定")
                .data("desc", fullHide ? "这是一篇付费专享文章，解锁后即可阅读全文内容" : "解锁全文即可查看此处及后续所有精彩内容")
                .data("showButton", true)
                .data("postRef", resolvePostRef(postId))
                .data("price", postPrice)
                .data("blockId", null)
                .data("buttonText", "立即解锁全文")
                .render() + "\n\n";
    }

    @FunctionalInterface
    private interface HiddenBlockRenderer {
        String render(String blockType, String attrStr, String body,
                      com.biliwind.blog.model.PostRenderType renderType);
    }

    /**
     * Replaces hidden-content shortcodes with server-owned templates. Authored
     * segments are sanitized independently and only then concatenated with the
     * generated HTML, so the sanitizer never sees (and can never strip) the
     * template's purchase controls while author-supplied buttons are still
     * removed.
     */
    private String renderHiddenBlocks(String rawContent,
                                      com.biliwind.blog.model.PostRenderType renderType,
                                      HiddenBlockRenderer renderer) {
        java.util.regex.Matcher matcher = HIDDEN_BLOCK_PATTERN.matcher(rawContent);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            out.append(renderAuthoredMarkup(rawContent.substring(cursor, matcher.start()), renderType));
            out.append(renderer.render(
                    matcher.group(1).toLowerCase(java.util.Locale.ROOT),
                    matcher.group(2),
                    matcher.group(3),
                    renderType));
            cursor = matcher.end();
        }
        out.append(renderAuthoredMarkup(rawContent.substring(cursor), renderType));
        return out.toString();
    }

    /** Substitutes self-closing templates without exposing them to the sanitizer. */
    private String renderAuthoredMarkup(String content,
                                        com.biliwind.blog.model.PostRenderType renderType) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher matcher = INLINE_TEMPLATE_PATTERN.matcher(content);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            out.append(renderAuthoredText(content.substring(cursor, matcher.start()), renderType));
            out.append(resolveInlineTemplate(matcher));
            cursor = matcher.end();
        }
        out.append(renderAuthoredText(content.substring(cursor), renderType));
        return out.toString();
    }

    private String renderAuthoredText(String authoredContent,
                                      com.biliwind.blog.model.PostRenderType renderType) {
        if (authoredContent == null || authoredContent.isBlank()) {
            return "";
        }
        com.biliwind.blog.model.PostRenderType effective = renderType == null
                ? com.biliwind.blog.model.PostRenderType.MARKDOWN : renderType;
        return switch (effective) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS ->
                    com.biliwind.blog.common.helper.MarkdownHelper.toHtml(authoredContent);
            default -> publicContentSanitizer.sanitize(authoredContent);
        };
    }

    private String resolveInlineTemplate(java.util.regex.Matcher matcher) {
        if (matcher.group(1) != null || matcher.group(2) != null) {
            String idStr = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            try {
                Long itemId = Long.parseLong(idStr);
                com.biliwind.blog.model.StoreItem item = com.biliwind.blog.model.StoreItem.findById(itemId);
                if (item == null) {
                    return "<!-- Store Item " + itemId + " Not Found -->";
                }
                return "\n\n" + storeItemCardTemplate
                        .data("name", item.name)
                        .data("price", item.price)
                        .data("desc", item.description != null ? item.description : "暂无描述")
                        .render() + "\n\n";
            } catch (Exception exception) {
                return "<!-- Invalid Store Item ID: " + idStr + " -->";
            }
        }
        if (matcher.group(3) != null) {
            return LEGACY_HIDE_IMAGE_HTML;
        }
        return LEGACY_ATTACHMENT_HTML;
    }

    /**
     * 从短代码标签中提取属性值
     */
    private String extractAttribute(String tag, String attrName) {
        if (tag == null || tag.isEmpty()) return null;
        // 匹配 attr="value" 或 attr=value
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                attrName + "\\s*=\\s*(?:\"([^\"]*)\"|([^\\s\\]]+))",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher m = p.matcher(tag);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private com.biliwind.blog.model.PostRevision resolvePublicContentRevision(Post post) {
        if (post == null) {
            return null;
        }
        return post.publishedRevision;
    }
}
