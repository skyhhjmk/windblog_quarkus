package com.biliwind.blog.context;

import com.biliwind.blog.common.constant.LanguageConstant;
import jakarta.enterprise.context.RequestScoped;

/**
 * 请求作用域的语言上下文 Bean
 * 存储当前请求的语言标识
 */
@RequestScoped
public class LanguageContext {
    private String lang = LanguageConstant.DEFAULT_LANG;
    private String defaultLang = LanguageConstant.DEFAULT_LANG;
    private boolean explicit;
    private String path = "/";
    private String query = "";
    private String contentLanguage = LanguageConstant.DEFAULT_LANG;
    private boolean resolved;

    // Getter & Setter
    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }


    public String getShortLang() {
        return lang.split("-")[0];
    }

    public String getDefaultLang() {
        return defaultLang;
    }

    public void setDefaultLang(String defaultLang) {
        if (defaultLang != null && !defaultLang.isBlank()) {
            this.defaultLang = defaultLang;
        }
    }

    public boolean isExplicit() {
        return explicit;
    }

    public void setExplicit(boolean explicit) {
        this.explicit = explicit;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        if (path != null && !path.isBlank()) {
            this.path = path.startsWith("/") ? path : "/" + path;
        }
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
    }

    /**
     * Applies the prefix for the current language, using the region default
     * language as the unprefixed canonical form.
     */
    public String localize(String path) {
        return com.biliwind.blog.common.helper.LanguageHelper.localizedPath(path, lang, defaultLang);
    }

    public String homeUrl() {
        return localize("/");
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public String getContentLanguage() {
        return contentLanguage;
    }

    public void setContentLanguage(String contentLanguage) {
        if (contentLanguage != null && !contentLanguage.isBlank()) {
            this.contentLanguage = contentLanguage;
        }
    }
}