package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.UserPurchaseRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;

/**
 * 预留访问控制服务层。
 * 当前使用 Active Record 方式，后续逐步迁移至 Service 分层。
 */
@ApplicationScoped
public class PostAccessService {

    public Post findBySlug(String slug) {
        return Post.find("slug = ?1 and deletedAt is null", slug)
                .firstResult();
    }

    public boolean isPrivate(Post post) {
        return post.visibility == 1;
    }

    public boolean isPasswordProtected(Post post) {
        return post.visibility == 2;
    }

    public boolean verifyPassword(Post post, String submittedPassword) {
        if (submittedPassword == null && post.password == null) {
            return true;
        }
        if (submittedPassword == null || post.password == null) {
            return false;
        }
        return submittedPassword.equals(post.password);
    }

    /**
     * 判断用户是否已购买此文章
     */
    public boolean hasPurchasedPost(Long userId, Long postId) {
        if (userId == null) return false;
        return UserPurchaseRecord.count("userId = ?1 and targetType = 'POST' and targetId = ?2", userId, postId) > 0;
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
        if (post.currentRevision != null && post.currentRevision.contentMarkdown != null) {
            long maxPrice = 0;
            // 匹配包含 hide-text 或 hide-attachment 的标签及其内部所有属性
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\\[\\s*(?:hide-text|hide-attachment)(.*?)\\]",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

            // 遍历所有语言版本，取价格总和最大的那个版本作为文章价格（避免多语言累加）
            for (String content : post.currentRevision.contentMarkdown.values()) {
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
     * 购买文章买断
     */
    @jakarta.transaction.Transactional
    public void buyPost(Long userId, Long postId, Long points) {
        if (userId == null) throw new BadRequestException("必须登录后才能购买");

        Post post = Post.findById(postId);
        if (post == null) throw new BadRequestException("文章不存在");

        if (hasPurchasedPost(userId, postId)) {
            throw new BadRequestException("您已经购买过此文章");
        }

        long postPrice = getPostPrice(post);
        long priceToPay = (points != null) ? points : postPrice;

        // 如果已经支付过相同或更高积分，则无需重复支付
        if (getMaxPointsPaid(userId, postId) >= priceToPay) {
            return;
        }

        // 扣除积分（如果价格大于0）
        if (priceToPay > 0) {
            WalletService walletService = jakarta.enterprise.inject.spi.CDI.current().select(WalletService.class).get();
            walletService.deductPoints(userId, priceToPay, "BUY_POST", postId, "解锁文章内容: " + post.slug);
        }

        // 记录购买流水
        UserPurchaseRecord record = new UserPurchaseRecord();
        record.userId = userId;
        record.targetType = "POST";
        record.targetId = postId;
        record.pointsPaid = priceToPay;
        record.persist();
    }

    /**
     * 过滤文章内容中的隐藏短代码，并替换为占位符提示。
     * 支持 [hide-text price=100]...[/hide-text]
     * 支持 [hide-attachment price=200]...[/hide-attachment]
     */
    /**
     * 根据购买状态过滤或清理隐藏内容标签
     *
     * @param rawContent   原始 Markdown 内容
     * @param hasPurchased 是否已购买
     * @return 处理后的内容
     */
    public String filterHiddenContent(String rawContent, long maxPointsPaid, boolean isAuthor, Long postId, long postPrice) {
        if (rawContent == null || rawContent.isEmpty()) return rawContent;

        // 全站买断判定：或者是作者，或者支付过文章全价（且总价 > 0），或者文章免费但点击过解锁（产生过0积分记录）
        boolean hasPurchased = isAuthor
                || (postPrice > 0 && maxPointsPaid >= postPrice)
                || (postPrice == 0 && maxPointsPaid >= 0);

        // 处理 [hide-text]
        java.util.regex.Pattern textPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-text(.*?)\\](.*?)\\[\\s*/hide-text\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher textMatcher = textPattern.matcher(rawContent);
        StringBuilder sb = new StringBuilder();
        while (textMatcher.find()) {
            String attrStr = textMatcher.group(1);
            String priceStr = extractAttribute(attrStr, "price");
            long requiredPrice = (priceStr != null && !priceStr.isEmpty()) ? Long.parseLong(priceStr) : 0;

            // 解锁逻辑：或者是全站买断（hasPurchased），或者支付过的最高积分 >= 该块要求的积分
            boolean isUnlocked = hasPurchased || (maxPointsPaid >= requiredPrice);

            if (isUnlocked) {
                // 已解锁：只保留内容，删掉标签
                textMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(textMatcher.group(2)));
            } else {
                // 未解锁：显示占位符
                String price = extractAttribute(attrStr, "price");
                String show = extractAttribute(attrStr, "show");

                String title = (show != null) ? show : "专享内容已锁定";
                boolean isFree = price == null || price.equals("0") || price.isEmpty();

                // 如果块没有指定价格，或者价格为0，则使用文章整体价格提示
                String displayPrice = (price != null && !price.equals("0")) ? price : String.valueOf(postPrice);
                String buttonText = isFree ? "免费解锁" : ("支付 " + displayPrice + " 积分解锁区块");
                String descText = isFree ? "当前为免费专享区块，解锁后即可阅读隐藏内容" : "解锁本文即可查看此处及后续所有精彩内容";

                String replacement = "\n\n<div class=\"md-region-premium-card\">\n" +
                        "    <div class=\"premium-card-body\">\n" +
                        "        <div class=\"premium-icon\">\n" +
                        "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                        "        </div>\n" +
                        "        <div class=\"premium-text\">\n" +
                        "            <p class=\"premium-title\">" + title + "</p>\n" +
                        "            <p class=\"premium-desc\">" + descText + "</p>\n" +
                        "        </div>\n" +
                        "        <button class=\"btn-action-primary buy-post-btn\" data-post-id=\"" + postId + "\" data-price=\"" + displayPrice + "\">\n" +
                        "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                        "            " + buttonText + "\n" +
                        "        </button>\n" +
                        "    </div>\n" +
                        "</div>\n\n";
                textMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
        }
        textMatcher.appendTail(sb);
        String filtered = sb.toString();

        // 处理 [hide-attachment]
        java.util.regex.Pattern attachPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-attachment(?:\\s+price\\s*=\\s*(\\d+))?\\s*\\](.*?)\\[\\s*/hide-attachment\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher attachMatcher = attachPattern.matcher(filtered);
        sb = new StringBuilder();
        while (attachMatcher.find()) {
            String priceStr = attachMatcher.group(1);
            long requiredPrice = (priceStr != null && !priceStr.isEmpty()) ? Long.parseLong(priceStr) : 0;

            // 附件也支持阶梯解锁，或者是全站买断
            boolean isUnlocked = hasPurchased || (maxPointsPaid >= requiredPrice);

            if (isUnlocked) {
                // 已购买：只保留内容，删掉标签
                attachMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(attachMatcher.group(2)));
            } else {
                // 未购买：显示占位符
                String price = attachMatcher.group(1);
                String label = (price != null && !price.equals("0")) ? "专属附件 (" + price + " 积分)" : "专属附件";
                String replacement = "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div><div class=\"md-region-content\"><strong>" + label + "已隐藏</strong>：解锁当前区块或购买整篇文章后可查看该专属附件。</div></div>\n\n";
                attachMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
        }
        attachMatcher.appendTail(sb);
        filtered = sb.toString();

        // 统一处理可能存在的旧版 [hide-image] 或 [attachment] (兼容性)
        filtered = filtered.replaceAll("\\[hide-image url=\"([^\"]+)\"\\]", "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div><div class=\"md-region-content\"><strong>付费图片已隐藏</strong>：购买文章后解锁。</div></div>\n\n");
        filtered = filtered.replaceAll("\\[attachment id=\"([^\"]+)\"\\]", "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div><div class=\"md-region-content\"><strong>专属附件已隐藏</strong>：购买文章后解锁。</div></div>\n\n");

        return filtered;
    }

    public Long getExtraPointsPrice(Post post) {
        if (post.extraInfo != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode extraNode = mapper.convertValue(post.extraInfo, com.fasterxml.jackson.databind.JsonNode.class);
                if (extraNode.has("points_price")) {
                    return extraNode.get("points_price").asLong(0);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }

    public int getFreeLines(Post post) {
        if (post.extraInfo != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode extraNode = mapper.convertValue(post.extraInfo, com.fasterxml.jackson.databind.JsonNode.class);
                if (extraNode.has("free_lines")) {
                    return extraNode.get("free_lines").asInt(0);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return 0;
    }

    /**
     * 获取仅包含免费预览内容的版本（用于初始页面加载）
     * 所有付费内容将被替换为占位符，且移除硬编码的HTML按钮
     */
    public String getPreviewOnlyContent(String content, int freeLines, boolean isPaid, Long postId, long postPrice) {
        if (content == null) return null;
        // 预览模式下假设未支付任何积分
        if (!isPaid) return filterHiddenContent(content, -1, false, postId, postPrice);

        // 先过滤隐藏内容（都替换为占位符）
        String filtered = filterHiddenContentForPreview(content, postId, postPrice);

        // 应用免费预览行数限制
        return applyFreePreviewForPreview(filtered, freeLines, postId, postPrice);
    }

    /**
     * 为预览版过滤隐藏内容（使用更简洁的占位符，无硬编码按钮）
     */
    private String filterHiddenContentForPreview(String rawContent, Long postId, long postPrice) {
        if (rawContent == null || rawContent.isEmpty()) return rawContent;

        // 处理 [hide-text]
        java.util.regex.Pattern textPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-text(.*?)\\](.*?)\\[\\s*/hide-text\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher textMatcher = textPattern.matcher(rawContent);
        StringBuilder sb = new StringBuilder();
        while (textMatcher.find()) {
            String attrStr = textMatcher.group(1);
            String show = extractAttribute(attrStr, "show");
            String title = (show != null) ? show : "专享内容已锁定";
            String priceAttr = extractAttribute(attrStr, "price");
            boolean isFree = priceAttr == null || priceAttr.equals("0") || priceAttr.isEmpty();
            String displayPrice = isFree ? String.valueOf(postPrice) : priceAttr;
            String buttonText = isFree ? "免费解锁" : ("支付 " + displayPrice + " 积分解锁区块");
            String descText = isFree ? "当前为免费专享区块，解锁后即可阅读隐藏内容" : "解锁本文即可查看此处及后续所有精彩内容";

            String replacement = "\n\n<div class=\"md-region-premium-card\">\n" +
                    "    <div class=\"premium-card-body\">\n" +
                    "        <div class=\"premium-icon\">\n" +
                    "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                    "        </div>\n" +
                    "        <div class=\"premium-text\">\n" +
                    "            <p class=\"premium-title\">" + title + "</p>\n" +
                    "            <p class=\"premium-desc\">" + descText + "</p>\n" +
                    "        </div>\n" +
                    "        <button class=\"btn-action-primary buy-post-btn\" data-post-id=\"" + postId + "\" data-price=\"" + postPrice + "\">\n" +
                    "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                    "            " + buttonText + "\n" +
                    "        </button>\n" +
                    "    </div>\n" +
                    "</div>\n\n";
            textMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        textMatcher.appendTail(sb);
        String filtered = sb.toString();

        // 处理 [hide-attachment]
        java.util.regex.Pattern attachPattern = java.util.regex.Pattern.compile(
                "\\[\\s*hide-attachment(?:\\s+price\\s*=\\s*(\\d+))?\\s*\\](.*?)\\[\\s*/hide-attachment\\s*\\]",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher attachMatcher = attachPattern.matcher(filtered);
        sb = new StringBuilder();
        while (attachMatcher.find()) {
            String replacement = "\n\n<div class=\"md-region md-region-info\"><div class=\"md-region-icon\"></div><div class=\"md-region-content\"><strong>专属附件已隐藏</strong>：购买整篇文章后可解锁并下载该专属附件。</div></div>\n\n";
            attachMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        attachMatcher.appendTail(sb);
        filtered = sb.toString();

        return filtered;
    }

    /**
     * 为预览版应用免费预览逻辑（使用data属性而非硬编码函数调用）
     */
    private String applyFreePreviewForPreview(String content, int freeLines, Long postId, long postPrice) {
        if (content == null) return null;

        String[] lines = content.split("\r?\n");
        if (lines.length <= freeLines && freeLines > 0) return content;

        StringBuilder sb = new StringBuilder();
        int limit = Math.max(0, freeLines);
        for (int i = 0; i < Math.min(lines.length, limit); i++) {
            sb.append(lines[i]).append("\n");
        }

        String placeholder = limit == 0 ? "\n\n<div class=\"md-region-premium-card full-hide\">\n" +
                "    <div class=\"premium-card-body\">\n" +
                "        <div class=\"premium-icon\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "        </div>\n" +
                "        <div class=\"premium-text\">\n" +
                "            <p class=\"premium-title\">本文内容已锁定</p>\n" +
                "            <p class=\"premium-desc\">这是一篇付费专享文章，解锁后即可阅读全文内容</p>\n" +
                "        </div>\n" +
                "        <button class=\"btn-action-primary buy-post-btn\" data-post-id=\"" + postId + "\" data-price=\"" + postPrice + "\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "            立即解锁全文\n" +
                "        </button>\n" +
                "    </div>\n" +
                "</div>\n\n"
                : "\n\n<div class=\"md-region-premium-card\">\n" +
                "    <div class=\"premium-card-body\">\n" +
                "        <div class=\"premium-icon\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "        </div>\n" +
                "        <div class=\"premium-text\">\n" +
                "            <p class=\"premium-title\">专享内容已锁定</p>\n" +
                "            <p class=\"premium-desc\">解锁全文即可查看此处及后续所有精彩内容</p>\n" +
                "        </div>\n" +
                "        <button class=\"btn-action-primary buy-post-btn\" data-post-id=\"" + postId + "\" data-price=\"" + postPrice + "\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "            立即解锁全文\n" +
                "        </button>\n" +
                "    </div>\n" +
                "</div>\n\n";

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

        String placeholder = limit == 0 ? "\n\n<div class=\"md-region-premium-card full-hide\">\n" +
                "    <div class=\"premium-card-body\">\n" +
                "        <div class=\"premium-icon\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "        </div>\n" +
                "        <div class=\"premium-text\">\n" +
                "            <p class=\"premium-title\">本文内容已锁定</p>\n" +
                "            <p class=\"premium-desc\">这是一篇付费专享文章，解锁后即可阅读全文内容</p>\n" +
                "        </div>\n" +
                "        <button class=\"btn-action-primary buy-post-btn\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "            立即解锁全文\n" +
                "        </button>\n" +
                "    </div>\n" +
                "</div>\n\n"
                : "\n\n<div class=\"md-region-premium-card\">\n" +
                "    <div class=\"premium-card-body\">\n" +
                "        <div class=\"premium-icon\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "        </div>\n" +
                "        <div class=\"premium-text\">\n" +
                "            <p class=\"premium-title\">专享内容已锁定</p>\n" +
                "            <p class=\"premium-desc\">解锁全文即可查看此处及后续所有精彩内容</p>\n" +
                "        </div>\n" +
                "        <button class=\"btn-action-primary buy-post-btn\">\n" +
                "            <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg>\n" +
                "            立即解锁全文\n" +
                "        </button>\n" +
                "    </div>\n" +
                "</div>\n\n";

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
}
