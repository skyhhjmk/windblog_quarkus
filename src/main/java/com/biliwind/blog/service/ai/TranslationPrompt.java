package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared translation instruction for every AI provider.
 * <p>
 * The prompt deliberately treats Markdown as a structure contract instead of
 * prose: translated articles must keep headings, lists, code, HTML, URLs and
 * hidden-block shortcodes byte-for-byte so the public renderer, media
 * references and access-control blocks keep working after translation.
 */
public final class TranslationPrompt {

    public static final String SYSTEM_PROMPT = "You are a meticulous technical translator. "
            + "You preserve Markdown structure exactly and translate only natural language.";

    public static final String DEFAULT_PROMPT = """
            你是专业技术文章译者。请把输入 JSON 中 fields 的自然语言从 sourceLanguage 翻译为 targetLanguage。
            硬性要求：
            1. 只输出一个 JSON 对象，键与输入 fields 完全一致，所有值必须是字符串；不要输出 Markdown 代码围栏、前后缀或任何解释。
            2. 只翻译自然语言。必须逐字保留：Markdown 结构（标题级别、列表、引用、表格、分隔线、空行）、代码块与行内代码、
               HTML 标签及属性、链接 URL、图片 URL、data-block-id、短代码（例如 [hide-text ...]、[hide-attachment ...]）、
               变量名、命令、路径、文件名、公式、slug 和任何标识符。
            3. 不新增、删除、合并或重排段落、标题、列表项、代码块、图片或链接；译文必须与原文保持一一对应的结构。
            4. 保留数字、日期、版本号、单位、标点风格和原有换行；代码块（包括注释）必须逐字保持不变，不要翻译或改动代码块内容。
            5. 不要翻译 URL、锚点、SEO 关键词、产品名、公司名和技术专有名词（行业通用译名除外）。
            6. 如果某个字段为空，返回空字符串；输出必须能被 JSON 解析器直接解析。
            输入：
            """;

    private TranslationPrompt() {
    }

    public static String build(String promptTemplate, String sourceLanguage, String targetLanguage,
                               Map<String, String> fields, ObjectMapper objectMapper) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceLanguage", sourceLanguage);
        input.put("targetLanguage", targetLanguage);
        input.put("fields", fields);
        String template = promptTemplate == null || promptTemplate.isBlank()
                ? DEFAULT_PROMPT : promptTemplate;
        return template + objectMapper.writeValueAsString(input);
    }

    public static String build(String sourceLanguage, String targetLanguage,
                               Map<String, String> fields, ObjectMapper objectMapper) throws Exception {
        return build(null, sourceLanguage, targetLanguage, fields, objectMapper);
    }
}
