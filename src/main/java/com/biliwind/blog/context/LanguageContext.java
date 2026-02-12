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
}