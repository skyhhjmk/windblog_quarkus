package com.biliwind.blog.service.elasticsearch;

/**
 * Elasticsearch 同步任务 DTO
 *
 * @param postId     文章 ID
 * @param actionType 操作类型: UPDATE, DELETE
 */
public record EsSyncTask(Long postId, String actionType) {
}
