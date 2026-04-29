package com.biliwind.blog.service;

import io.quarkus.arc.Arc;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;

@TemplateData(namespace = "config")
@ApplicationScoped
public class ConfigTemplateData {

    private static ConfigManager configManager() {
        return Arc.container().instance(ConfigManager.class).get();
    }

    public static String siteTitle() {
        return configManager().getString("site_info", "title", "WindBlog");
    }

    public static String siteSubtitle() {
        return configManager().getString("site_info", "subtitle", "");
    }

    public static String siteDescription() {
        return configManager().getString("site_info", "description", "");
    }

    public static String siteKeywords() {
        var node = configManager().get("site_info");
        if (node != null && node.has("keywords") && node.get("keywords").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (var item : node.get("keywords")) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(item.asText());
            }
            return sb.toString();
        }
        return "";
    }

    public static String copyright() {
        return configManager().getString("site_footer", "copyright", "© 2026 WindBlog. Built with Quarkus.");
    }

    public static String icp() {
        return configManager().getString("site_footer", "icp", "");
    }

    public static String footerHtml() {
        return configManager().getString("site_footer", "custom_html", "");
    }

    public static String logoUrl() {
        return configManager().getString("appearance", "logo_url", "/logo.png");
    }

    public static String faviconUrl() {
        return configManager().getString("appearance", "favicon_url", "/favicon.ico");
    }

    public static String themeColor() {
        return configManager().getString("appearance", "theme_color", "#0A0C10");
    }

    public static String social(String platform) {
        return configManager().getString("social_links", platform, "");
    }

    public static boolean toggle(String key) {
        return configManager().getBoolean("feature_toggles", key, true);
    }
}
