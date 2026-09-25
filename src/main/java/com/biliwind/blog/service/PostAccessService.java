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

    @Inject
    @io.quarkus.qute.Location("system/components/premium_card.html")
    io.quarkus.qute.Template premiumCardTemplate;

    @Inject
    @io.quarkus.qute.Location("system/components/premium_attachment.html")
    io.quarkus.qute.Template premiumAttachmentTemplate;

    @Inject
    @io.quarkus.qute.Location("system/components/store_item_card.html")
    io.quarkus.qute.Template storeItemCardTemplate;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    ContentAccessTicketService contentAccessTicketService;

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
     * inline images and other media references in protected posts.
     */
    public boolean isProtectedMediaReference(PostMedia reference) {
        if (reference == null) {
            return false;
        }
        return reference.usageType == 3 || isProtectedPost(reference.post);
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
        if (rawContent == null || rawContent.isEmpty()) return rawContent;
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
        StringBuilder sb = new StringBuilder();
        while (textMatcher.find()) {
            String attrStr = textMatcher.group(1);
            String content = textMatcher.group(2);

            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, content);
            
            String priceStr = extractAttribute(attrStr, "price");
            long requiredPrice = (priceStr != null && !priceStr.isEmpty()) ? Long.parseLong(priceStr) : 0;

            // 解锁逻辑：或者是全站买断（fullUnlocked），或者已经购买过这个特定的 blockId，或者是免费区块
            boolean isUnlocked = fullUnlocked || (requiredPrice == 0) || hasPurchasedBlock(userId, postId, blockId);

            if (isUnlocked) {
                // 已解锁：只保留内容，删掉标签
                textMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(content));
            } else {
                // 未解锁：显示占位符
                String title = extractAttribute(attrStr, "show");
                if (title == null) title = "专享内容已锁定";

                boolean isFree = requiredPrice == 0;
                String displayPrice = String.valueOf(requiredPrice > 0 ? requiredPrice : postPrice);
                String buttonText = isFree ? "免费解锁" : ("支付 " + displayPrice + " 积分解锁区块");
                String descText = isFree ? "当前为免费专享区块，解锁后即可阅读隐藏内容" : "解锁该区块即可查看精彩内容，或购买全文解锁更多";

                String replacement = "\n\n" + premiumCardTemplate
                        .data("fullHide", false)
                        .data("title", title)
                        .data("desc", descText)
                        .data("showButton", true)
                        .data("postRef", postRef)
                        .data("price", displayPrice)
                        .data("blockId", blockId)
                        .data("buttonText", buttonText)
                        .render() + "\n\n";
                textMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
        }
        textMatcher.appendTail(sb);
        String filtered = sb.toString();

        // 处理 [hide-attachment]
        java.util.regex.Pattern attachPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-attachment(.*?)\\](.*?)\\[\\s*/hide-attachment\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher attachMatcher = attachPattern.matcher(filtered);
        sb = new StringBuilder();
        while (attachMatcher.find()) {
            String attrStr = attachMatcher.group(1);
            String content = attachMatcher.group(2);

            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, content);

            String priceStr = extractAttribute(attrStr, "price");
            long requiredPrice = (priceStr != null && !priceStr.isEmpty()) ? Long.parseLong(priceStr) : 0;

            // 附件也支持区块解锁，免费附件直接解锁
            boolean isUnlocked = fullUnlocked || (requiredPrice == 0) || hasPurchasedBlock(userId, postId, blockId);

            if (isUnlocked) {
                // 已购买：只保留内容，删掉标签
                attachMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(content));
            } else {
                // 未购买：显示占位符
                boolean isFree = requiredPrice == 0;
                String label = (requiredPrice > 0 ? "专享资源 (" + requiredPrice + " 积分)" : "专享资源");
                String displayPrice = String.valueOf(requiredPrice > 0 ? requiredPrice : postPrice);
                String buttonText = isFree ? "免费解锁附件" : ("支付 " + displayPrice + " 积分解锁附件");
                String descText = isFree ? "当前为免费资源，解锁后即可获取下载链接" : "解锁该资源即可获取下载链接，或购买全文解锁更多";

                String replacement = "\n\n" + premiumAttachmentTemplate
                        .data("title", label)
                        .data("desc", descText)
                        .data("showButton", true)
                        .data("postRef", postRef)
                        .data("price", displayPrice)
                        .data("blockId", blockId)
                        .data("buttonText", buttonText)
                        .render() + "\n\n";
                attachMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }

        }
        attachMatcher.appendTail(sb);
        filtered = sb.toString();

        return processStoreItems(filtered);
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
                unlockedBlocks.put(blockId, content);
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
            if (fullUnlocked || (requiredPrice == 0) || hasPurchasedBlock(userId, postId, blockId)) {
                unlockedBlocks.put(blockId, content);
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
        String cacheKey = "post:preview:v2:" + post.id + ":" + lang + ":" + revisionStr;

        java.util.Optional<String> cached = cacheService.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        int freeLines = getFreeLines(post);
        String previewContent = getPreviewOnlyContent(localizedContent, freeLines, postPrice > 0, post.id, postPrice, null);

        cacheService.set(cacheKey, previewContent, java.time.Duration.ofDays(7));
        return previewContent;
    }

    /**
     * 获取仅包含免费预览内容的版本（用于初始页面加载）
     * 所有付费内容将被替换为占位符，且移除硬编码的HTML按钮
     */
    public String getPreviewOnlyContent(String content, int freeLines, boolean isPaid, Long postId, long postPrice, Long userId) {
        if (content == null) return null;
        // 预览模式下假设未支付任何积分
        if (!isPaid) {
            String filteredFreeContent = filterHiddenContent(content, -1, false, postId, postPrice, userId);
            return removeProtectedMediaReferences(postId, filteredFreeContent);
        }

        // 先过滤隐藏内容（都替换为占位符）
        String filtered = filterHiddenContentForPreview(content, postId, postPrice, userId);

        // 应用免费预览行数限制
        String preview = applyFreePreviewForPreview(filtered, freeLines, postId, postPrice);
        return removeProtectedMediaReferences(postId, preview);
    }

    public String rewriteProtectedMediaReferences(Long postId, String content, Long userId,
                                                   boolean authorized, String deviceId) {
        if (content == null || content.isBlank() || postId == null) {
            return content;
        }
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
                    if (!authorized || userId == null) {
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
                    "placeholderUrl", "coverUrl")) {
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
     * 为预览版过滤隐藏内容（使用更简洁的占位符，无硬编码按钮）
     */
    private String filterHiddenContentForPreview(String rawContent, Long postId, long postPrice, Long userId) {
        if (rawContent == null || rawContent.isEmpty()) return rawContent;
        String postRef = resolvePostRef(postId);

        // 处理 [hide-text]
        java.util.regex.Pattern textPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-text(.*?)\\](.*?)\\[\\s*/hide-text\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher textMatcher = textPattern.matcher(rawContent);
        StringBuilder sb = new StringBuilder();
        while (textMatcher.find()) {
            String attrStr = textMatcher.group(1);
            String content = textMatcher.group(2);

            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, content);
            
            String show = extractAttribute(attrStr, "show");
            String title = (show != null) ? show : "专享内容已锁定";
            String priceAttr = extractAttribute(attrStr, "price");
            boolean isFree = priceAttr == null || priceAttr.equals("0") || priceAttr.isEmpty();
            String displayPrice = isFree ? String.valueOf(postPrice) : priceAttr;
            String buttonText = isFree ? "免费解锁" : ("支付 " + displayPrice + " 积分解锁区块");
            String descText = isFree ? "当前为免费专享区块，解锁后即可阅读隐藏内容" : "解锁该区块即可查看精彩内容，或购买全文解锁更多";

            String replacement = "\n\n" + premiumCardTemplate
                    .data("fullHide", false)
                    .data("title", title)
                    .data("desc", descText)
                    .data("showButton", true)
                    .data("postRef", postRef)
                    .data("price", displayPrice)
                    .data("blockId", blockId)
                    .data("buttonText", buttonText)
                    .render() + "\n\n";
            textMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        textMatcher.appendTail(sb);
        String filtered = sb.toString();

        // 处理 [hide-attachment]
        java.util.regex.Pattern attachPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-attachment(.*?)\\](.*?)\\[\\s*/hide-attachment\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher attachMatcher = attachPattern.matcher(filtered);
        sb = new StringBuilder();
        while (attachMatcher.find()) {
            String attrStr = attachMatcher.group(1);
            String content = attachMatcher.group(2);
            String idAttr = extractAttribute(attrStr, "id");
            String blockId = (idAttr != null && !idAttr.isEmpty()) ? idAttr : generateBlockId(attrStr, content);
            String priceAttr = extractAttribute(attrStr, "price");
            boolean isFree = priceAttr == null || priceAttr.equals("0") || priceAttr.isEmpty();
            String displayPrice = isFree ? String.valueOf(postPrice) : priceAttr;
            String buttonText = isFree ? "免费解锁附件" : ("支付 " + displayPrice + " 积分解锁附件");

            String replacement = "\n\n" + premiumAttachmentTemplate
                    .data("title", "专属附件已隐藏")
                    .data("desc", "您可以解锁当前区块或购买整篇文章后查看该专属附件。")
                    .data("showButton", true)
                    .data("postRef", postRef)
                    .data("price", displayPrice)
                    .data("blockId", blockId)
                    .data("buttonText", buttonText)
                    .render() + "\n\n";
            attachMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        attachMatcher.appendTail(sb);
        filtered = sb.toString();

        // 处理 [store-item id=XXX] (预览版也显示)
        java.util.regex.Pattern storePattern = java.util.regex.Pattern.compile(
                "\\[\\s*store-item\\s+id\\s*=\\s*(?:\"([^\"]*)\"|([^\\s\\]]+))\\s*\\]",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher storeMatcher = storePattern.matcher(filtered);
        sb = new StringBuilder();
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
        return sb.toString();
    }


    /**
     * 为预览版应用免费预览逻辑（使用data属性而非硬编码函数调用）
     */
    private String applyFreePreviewForPreview(String content, int freeLines, Long postId, long postPrice) {
        if (content == null) return null;
        String postRef = resolvePostRef(postId);

        String[] lines = content.split("\r?\n");
        if (lines.length <= freeLines && freeLines > 0) return content;

        StringBuilder sb = new StringBuilder();
        int limit = Math.max(0, freeLines);
        for (int i = 0; i < Math.min(lines.length, limit); i++) {
            sb.append(lines[i]).append("\n");
        }

        String placeholder = "\n\n" + premiumCardTemplate
                .data("fullHide", limit == 0)
                .data("title", limit == 0 ? "本文内容已锁定" : "专享内容已锁定")
                .data("desc", limit == 0 ? "这是一篇付费专享文章，解锁后即可阅读全文内容" : "解锁全文即可查看此处及后续所有精彩内容")
                .data("showButton", true)
                .data("postRef", postRef)
                .data("price", postPrice)
                .data("blockId", null)
                .data("buttonText", "立即解锁全文")
                .render() + "\n\n";

        sb.append(placeholder);
        return sb.toString();
    }

    /**
     * 应用免费预览逻辑（保持原有方法用于向后兼容）
     */
    public String applyFreePreview(String content, int freeLines, boolean isPaid) {
        if (content == null) return null;
        if (!isPaid) return content;

        String[] lines = content.split("\r?\n");
        if (lines.length <= freeLines && freeLines > 0) return content;

        StringBuilder sb = new StringBuilder();
        int limit = Math.max(0, freeLines);
        for (int i = 0; i < Math.min(lines.length, limit); i++) {
            sb.append(lines[i]).append("\n");
        }

        String placeholder = "\n\n" + premiumCardTemplate
                .data("fullHide", limit == 0)
                .data("title", limit == 0 ? "本文内容已锁定" : "专享内容已锁定")
                .data("desc", limit == 0 ? "这是一篇付费专享文章，解锁后即可阅读全文内容" : "解锁全文即可查看此处及后续所有精彩内容")
                .data("showButton", true)
                .data("postRef", null)
                .data("price", null)
                .data("blockId", null)
                .data("buttonText", "立即解锁全文")
                .render() + "\n\n";

        sb.append(placeholder);
        return sb.toString();
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
