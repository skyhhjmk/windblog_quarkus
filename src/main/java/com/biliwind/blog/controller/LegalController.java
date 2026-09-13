package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Context;
import com.biliwind.blog.service.ConfigManager;
import com.biliwind.blog.service.PublicContentSanitizer;

@Path("/")
public class LegalController {

    @Inject
    ConfigManager configManager;

    @Inject
    PublicContentSanitizer sanitizer;

    @Inject
    @Location("legal/terms.html")
    Template termsTemplate;

    @Inject
    @Location("legal/terms.content.html")
    Template termsContentTemplate;

    @Inject
    @Location("legal/privacy.html")
    Template privacyTemplate;

    @Inject
    @Location("legal/privacy.content.html")
    Template privacyContentTemplate;

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? termsContentTemplate : termsTemplate;
        return template.data("language", "zh-CN")
                .data("legalContent", sanitizer.sanitize(configManager.getString("legal_terms", "html", DEFAULT_TERMS)));
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? privacyContentTemplate : privacyTemplate;
        return template.data("language", "zh-CN")
                .data("legalContent", sanitizer.sanitize(configManager.getString("legal_privacy", "html", DEFAULT_PRIVACY)));
    }

    private static final String DEFAULT_TERMS = "<h1>用户协议</h1><p>欢迎使用 WindBlog。注册和使用本站服务即表示您同意遵守本协议以及适用的法律法规。</p><h2>账号</h2><p>您应提供真实、准确的注册信息并妥善保管账号凭据。不得冒用他人身份或利用本站从事违法活动。</p><h2>内容</h2><p>您对自行发布的内容负责，不得发布侵犯他人权利、恶意程序或违反法律法规的内容。我们可能依法处理违规内容。</p><h2>服务变更</h2><p>在必要时我们会调整功能或维护服务，并尽量提前告知重大变化。</p>";
    private static final String DEFAULT_PRIVACY = "<h1>隐私政策</h1><p>我们只在提供账号、评论、订阅和安全功能所必需的范围内处理个人信息。</p><h2>收集的信息</h2><p>注册时会保存用户名、邮箱和密码哈希。密码重置令牌只保存哈希值，并在使用或过期后失效。</p><h2>邮件</h2><p>邮箱验证、密码重置和您主动订阅的通知会通过站点配置的邮件服务发送。我们不会在页面或日志中展示密码和令牌。</p><h2>您的权利</h2><p>如需更正或删除账号信息，请通过站点公布的联系方式联系管理员。</p>";
}
