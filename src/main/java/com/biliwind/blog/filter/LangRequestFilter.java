package com.biliwind.blog.filter;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

@Provider
public class LangRequestFilter implements ContainerRequestFilter {

    @Inject
    LanguageContext languageContext;

    public static final String LANG_ATTRIBUTE = "REQUEST_LANG";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String finalLang = null;

        // 1) URL 路径语言前缀，例如 /en/post/hello
        finalLang = LanguageHelper.resolveLangFromPath(requestContext.getUriInfo().getPath());

        // 2) query 参数 ?lang=...
        List<String> langParams = requestContext.getUriInfo().getQueryParameters().get("lang");
        if (finalLang == null && langParams != null && !langParams.isEmpty()) {
            finalLang = LanguageHelper.normalizeToSupportedLang(langParams.getFirst());
        }

        // 3) Cookie
        if (finalLang == null) {
            String cookieStr = requestContext.getHeaders().getFirst(LanguageConstant.HEADER_COOKIE);
            if (cookieStr != null && cookieStr.contains(LanguageConstant.COOKIE_LANG_KEY + "=")) {
                String cookieLang = cookieStr.split(LanguageConstant.COOKIE_LANG_KEY + "=")[1]
                        .split(";")[0]
                        .trim();
                finalLang = LanguageHelper.normalizeToSupportedLang(cookieLang);
            }
        }

        // 4) Accept-Language
        if (finalLang == null) {
            String acceptLang = requestContext.getHeaders().getFirst(LanguageConstant.HEADER_ACCEPT_LANG);
            if (acceptLang != null && !acceptLang.isBlank()) {
                finalLang = LanguageHelper.normalizeToSupportedLang(acceptLang.split(",")[0].trim());
            }
        }

        if (finalLang == null || !LanguageConstant.SUPPORT_LANGS.contains(finalLang)) {
            finalLang = LanguageConstant.DEFAULT_LANG;
        }

        languageContext.setLang(finalLang);
        requestContext.getHeaders().putSingle(LanguageConstant.HEADER_REQUEST_LANG, finalLang);

        if (!finalLang.isEmpty()) {
            requestContext.setProperty(LANG_ATTRIBUTE, finalLang);
        }
    }
}
