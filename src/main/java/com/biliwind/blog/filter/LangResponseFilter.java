package com.biliwind.blog.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.biliwind.blog.common.constant.LanguageConstant.COOKIE_LANG_KEY;

@Provider
public class LangResponseFilter implements ContainerResponseFilter {

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // 从请求属性中获取之前解析的lang值
        String lang = (String) requestContext.getProperty(LangRequestFilter.LANG_ATTRIBUTE);

        if (lang != null && !lang.isEmpty()) {
            String encodedLang = URLEncoder.encode(lang, StandardCharsets.UTF_8);
            Cookie existingCookie = requestContext.getCookies().get(COOKIE_LANG_KEY);
            if (existingCookie != null && encodedLang.equals(existingCookie.getValue())) {
                // 已有相同语言 Cookie，不重复写入响应。
            } else {
                NewCookie langCookie = new NewCookie.Builder(COOKIE_LANG_KEY) // 指定Cookie名称
                        .value(encodedLang)                          // Cookie值
                        .path("/")                                   // Cookie生效路径（全站有效）
                        .maxAge(86400 * 30)                          // 过期时间（秒），30天
                        .secure(cookieSecure)
                        .httpOnly(true)                              // 禁止JS读取，防XSS
                        .sameSite(NewCookie.SameSite.LAX)              // 可选：设置SameSite属性，增强CSRF防护
                        .build();                                    // 构建NewCookie实例

                // 将Cookie添加到响应头
                responseContext.getHeaders().add(HttpHeaders.SET_COOKIE, langCookie);
            }
        }

        if (CsrfFilter.isCacheableHtmlResponse(responseContext.getMediaType(),
                responseContext.getHeaderString(HttpHeaders.CACHE_CONTROL))) {
            // Language and authentication cookies affect public HTML. Do not let a
            // shared cache serve one cookie context to another visitor.
            responseContext.getHeaders().putSingle(HttpHeaders.VARY, "Cookie");
            if (lang != null && !lang.isEmpty()
                    && (requestContext.getCookies().get(COOKIE_LANG_KEY) == null
                    || !URLEncoder.encode(lang, StandardCharsets.UTF_8)
                    .equals(requestContext.getCookies().get(COOKIE_LANG_KEY).getValue()))) {
                responseContext.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, "no-store");
            }
        }
    }
}
