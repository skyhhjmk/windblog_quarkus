package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ai.AiResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiArticleDraftAuthorTest {

    @Test
    void aiCreatedArticleSetsAuthorNameToModelName() {
        AiResult aiResult = new AiResult();
        aiResult.modelId = "gemini-2.5-pro";
        aiResult.provider = "GOOGLE";
        aiResult.contents.put("text", "正文内容");

        AiArticleDraftService.DraftPayload draft = new AiArticleDraftService.DraftPayload(
                "标题", "摘要", "# 标题\n\n正文", "zh-cn", aiResult
        );

        String modelName = "";
        if (draft.aiResult() != null && draft.aiResult().modelId != null && !draft.aiResult().modelId.isBlank()) {
            modelName = draft.aiResult().modelId.trim();
        }

        Post post = new Post();
        User operator = new User();
        operator.username = "admin";
        post.user = operator;
        post.authorName = modelName.isBlank() ? null : modelName;

        assertEquals("gemini-2.5-pro", post.authorName);
        assertEquals("gemini-2.5-pro", PostAuthorHelper.displayName(post));
    }

    @Test
    void administratorCanModifyAuthorNameToAnotherValueOrClear() {
        Post post = new Post();
        User operator = new User();
        operator.username = "admin";
        post.user = operator;
        post.authorName = "gemini-2.5-pro";
        assertEquals("gemini-2.5-pro", PostAuthorHelper.displayName(post));

        // Administrator changes author to custom name
        post.authorName = "Claude 3.7 Sonnet";
        assertEquals("Claude 3.7 Sonnet", PostAuthorHelper.displayName(post));

        // Administrator changes author to another user account
        User editor = new User();
        editor.username = "editor_alice";
        post.user = editor;
        post.authorName = null; // cleared custom name
        assertEquals("editor_alice", PostAuthorHelper.displayName(post));
    }
}
