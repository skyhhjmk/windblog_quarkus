package com.biliwind.blog.controller.api.admin.dto;

import java.util.List;

/**
 * 数据导入相关 DTO
 */
public class AdminImportDtos {

    /**
     * 测试连接请求
     */
    public record TestConnectionRequest(
            String driver,
            String url,
            String username,
            String password
    ) {
    }

    /**
     * 导入请求
     */
    public record ImportRequest(
            String driver,
            String url,
            String username,
            String password,
            List<String> types, // categories, tags, posts, links, comments, media
            String assetPrefix, // 附件相对地址补全前缀
            boolean clearExisting // 是否在导入前清空现有数据（慎用）
    ) {
    }

    /**
     * 导入结果
     */
    public record ImportResult(
            boolean success,
            String message,
            int importedCategories,
            int importedTags,
            int importedPosts,
            int importedLinks,
            int importedComments
    ) {
    }

    /**
     * 导入进度事件（用于 SSE）
     */
    public record ImportProgressEvent(
            String type,      // info, error, progress, end
            String message,   // 描述信息
            Object data       // 附加数据
    ) {
    }
}
