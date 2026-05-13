package com.biliwind.blog.common.constant;

import java.util.HashSet;
import java.util.Set;

/**
 * 语言常量类：统一管理支持的语言、默认语言、Cookie/请求头标识
 * 避免硬编码字符串，便于后续扩展语言
 */
public class LanguageConstant {
    /**
     * 简体中文
     */
    public static final String LANG_ZH_CN = "zh-cn"; // 简体中文
    /**
     * 美式英语
     */
    public static final String LANG_EN_US = "en-US"; // 美式英语

    /**
     * 默认语言
     */
    public static final String DEFAULT_LANG = LANG_ZH_CN;

    /**
     * 支持的语言集合
     */
    public static final Set<String> SUPPORT_LANGS = new HashSet<>();
    /**
     * HTTP标准Cookie请求头名
     */
    public static final String HEADER_COOKIE = "Cookie";
    /**
     * 存储语言的Cookie键名
     */
    public static final String COOKIE_LANG_KEY = "user_lang";
    /**
     * 透传语言的自定义请求头
     */
    public static final String HEADER_REQUEST_LANG = "X-Request-Lang"; //
    /**
     * 浏览器默认语言请求头
     */
    public static final String HEADER_ACCEPT_LANG = "Accept-Language"; //

    static {
        SUPPORT_LANGS.add(LANG_ZH_CN);
        SUPPORT_LANGS.add(LANG_EN_US);
    }
}