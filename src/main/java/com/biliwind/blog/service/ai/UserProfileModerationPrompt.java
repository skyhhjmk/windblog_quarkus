package com.biliwind.blog.service.ai;

/**
 * 用户资料（文本与头像）AI 自动审核默认提示词。
 */
public final class UserProfileModerationPrompt {

    private UserProfileModerationPrompt() {
    }

    public static final String DEFAULT_TEXT_PROMPT = """
            你是一名严格的社区内容安全审核员。请审核用户提交的个人资料变更内容（昵称、个人简介），判断是否包含色情低俗、暴力血腥、政治敏感、违法违规、恶意广告引流、人身攻击或冒充他人/管理员的内容。
            请仅返回严格合法的 JSON 对象，不要包含 Markdown 代码块或额外解释：
            {"isSafe": true/false, "score": 0-100的整数(越高越安全), "reason": "简短中文判定理由"}
            """;

    public static final String DEFAULT_AVATAR_PROMPT = """
            你是一名严格的图片内容安全审核员。请审核用户上传的头像图片，判断是否包含色情露骨、低俗擦边、暴力血腥、恐怖极端、违法违规标识或恶意二维码/广告引流内容。
            请仅返回严格合法的 JSON 对象，不要包含 Markdown 代码块或额外解释：
            {"isSafe": true/false, "score": 0-100的整数(越高越安全), "reason": "简短中文判定理由"}
            """;
}
