package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single source of truth for the article repost policies exposed by the
 * editor and rendered on public article pages.
 */
@ApplicationScoped
public class RepostPolicyCatalog {

    public static final String REQUEST_REQUIRED = "REQUEST_REQUIRED";
    public static final String CC_BY_4_0 = "CC_BY_4_0";
    public static final String CC_BY_SA_4_0 = "CC_BY_SA_4_0";
    public static final String CC_BY_NC_4_0 = "CC_BY_NC_4_0";
    public static final String CC_BY_NC_SA_4_0 = "CC_BY_NC_SA_4_0";
    public static final String CC_BY_ND_4_0 = "CC_BY_ND_4_0";
    public static final String CC_BY_NC_ND_4_0 = "CC_BY_NC_ND_4_0";
    public static final String CC0_1_0 = "CC0_1_0";

    private static final Policy REQUEST_POLICY = new Policy(
            REQUEST_REQUIRED,
            "转载需申请授权",
            "Repost requires authorization",
            null,
            true,
            "转载需先申请授权，并保留授权码、原文地址和文中 go/affiliate 链接。",
            "Request authorization before reposting and retain the authorization code, original URL, and go/affiliate links.",
            List.of(
                    "转载前需要申请授权",
                    "转载页需保留授权码、原文地址和文中 go/affiliate 链接"),
            List.of(
                    "Authorization is required before reposting",
                    "Keep the authorization code, original URL, and go/affiliate links on the reposted page"));

    private static final List<Policy> POLICIES = List.of(
            REQUEST_POLICY,
            new Policy(
                    CC_BY_4_0,
                    "CC BY 4.0",
                    "CC BY 4.0",
                    "https://creativecommons.org/licenses/by/4.0/deed.zh-hans",
                    false,
                    "无须申请转载；允许转载、改编和商业使用，需署名、标注原文地址并附协议链接。",
                    "No application is required; sharing, adaptation, and commercial use are allowed with attribution, the original URL, and a link to the license.",
                    List.of("允许转载、改编和商业使用", "需署名", "需标注原文地址", "需附上本协议链接"),
                    List.of("Sharing, adaptation, and commercial use are allowed", "Attribution is required", "Include the original URL", "Include a link to this license")),
            new Policy(
                    CC_BY_SA_4_0,
                    "CC BY-SA 4.0",
                    "CC BY-SA 4.0",
                    "https://creativecommons.org/licenses/by-sa/4.0/deed.zh-hans",
                    false,
                    "无须申请转载；允许转载、改编和商业使用，需署名、标注原文地址、附协议链接，改编内容需使用相同协议。",
                    "No application is required; sharing, adaptation, and commercial use are allowed with attribution, the original URL, a license link, and the same license for adaptations.",
                    List.of("允许转载、改编和商业使用", "需署名", "需标注原文地址", "需附上本协议链接", "改编内容需使用相同协议"),
                    List.of("Sharing, adaptation, and commercial use are allowed", "Attribution is required", "Include the original URL", "Include a link to this license", "Adaptations must use the same license")),
            new Policy(
                    CC_BY_NC_4_0,
                    "CC BY-NC 4.0",
                    "CC BY-NC 4.0",
                    "https://creativecommons.org/licenses/by-nc/4.0/deed.zh-hans",
                    false,
                    "无须申请转载；仅限非商业转载，需署名并标注原文地址。",
                    "No application is required; non-commercial sharing is allowed with attribution and the original URL.",
                    List.of("仅限非商业转载", "需署名", "需标注原文地址"),
                    List.of("Non-commercial sharing only", "Attribution is required", "Include the original URL")),
            new Policy(
                    CC_BY_NC_SA_4_0,
                    "CC BY-NC-SA 4.0",
                    "CC BY-NC-SA 4.0",
                    "https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans",
                    false,
                    "无须申请转载；仅限非商业转载，需署名、标注原文地址，改编内容需使用相同协议。",
                    "No application is required; non-commercial sharing is allowed with attribution, the original URL, and the same license for adaptations.",
                    List.of("仅限非商业转载", "需署名", "需标注原文地址", "改编内容需使用相同协议"),
                    List.of("Non-commercial sharing only", "Attribution is required", "Include the original URL", "Adaptations must use the same license")),
            new Policy(
                    CC_BY_ND_4_0,
                    "CC BY-ND 4.0",
                    "CC BY-ND 4.0",
                    "https://creativecommons.org/licenses/by-nd/4.0/deed.zh-hans",
                    false,
                    "无须申请转载；可转载但不得改编，需署名并标注原文地址。",
                    "No application is required; sharing is allowed but adaptations are not, with attribution and the original URL.",
                    List.of("可转载但不得改编", "需署名", "需标注原文地址"),
                    List.of("Sharing is allowed but adaptations are not", "Attribution is required", "Include the original URL")),
            new Policy(
                    CC_BY_NC_ND_4_0,
                    "CC BY-NC-ND 4.0",
                    "CC BY-NC-ND 4.0",
                    "https://creativecommons.org/licenses/by-nc-nd/4.0/deed.zh-hans",
                    false,
                    "无须申请转载；仅限非商业转载且不得改编，需署名并标注原文地址。",
                    "No application is required; non-commercial sharing only, no adaptations, with attribution and the original URL.",
                    List.of("仅限非商业转载", "不得改编", "需署名", "需标注原文地址"),
                    List.of("Non-commercial sharing only", "No adaptations", "Attribution is required", "Include the original URL")),
            new Policy(
                    CC0_1_0,
                    "CC0 1.0",
                    "CC0 1.0",
                    "https://creativecommons.org/publicdomain/zero/1.0/deed.zh-hans",
                    false,
                    "无须申请转载；不强制署名，建议保留原文地址。",
                    "No application is required; attribution is not required, but retaining the original URL is recommended.",
                    List.of("无须申请转载", "不强制署名", "建议保留原文地址"),
                    List.of("No application is required", "Attribution is not required", "Retaining the original URL is recommended")));

    private static final Map<String, Policy> BY_CODE = POLICIES.stream()
            .collect(Collectors.toUnmodifiableMap(Policy::code, Function.identity()));

    public List<Policy> list() {
        return POLICIES;
    }

    /** Resolves a missing or legacy value without rejecting old public snapshots. */
    public Policy resolve(Post post) {
        if (post == null) {
            return REQUEST_POLICY;
        }
        return resolve(post.repostPolicyCode, post.contentDeclarations);
    }

    /** Resolves a public snapshot value, including the legacy content declaration fallback. */
    public Policy resolve(String dedicatedCode, List<String> contentDeclarations) {
        if (dedicatedCode != null && !dedicatedCode.isBlank()) {
            return BY_CODE.getOrDefault(dedicatedCode.trim(), REQUEST_POLICY);
        }
        if (contentDeclarations != null && contentDeclarations.contains(CC_BY_NC_4_0)) {
            return BY_CODE.get(CC_BY_NC_4_0);
        }
        return REQUEST_POLICY;
    }

    /** Validates admin input. Empty values intentionally select the default policy. */
    public Policy require(String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return REQUEST_POLICY;
        }
        Policy policy = BY_CODE.get(requestedCode.trim());
        if (policy == null) {
            throw new BadRequestException("未知文章转载协议: " + requestedCode);
        }
        return policy;
    }

    public String buildDirectCopyText(Policy policy, String title, String originalUrl) {
        Objects.requireNonNull(policy, "policy");
        StringBuilder text = new StringBuilder();
        text.append("本文使用 ").append(policy.name()).append(" 协议，无须申请转载。\n");
        text.append("转载条件：\n");
        for (String condition : policy.conditions()) {
            text.append("- ").append(condition).append("\n");
        }
        text.append("\n");
        if (title != null && !title.isBlank()) {
            text.append("原文标题：").append(title.trim()).append("\n");
        }
        text.append("原文地址：").append(originalUrl).append("\n");
        text.append("协议链接：").append(policy.licenseUrl()).append("\n");
        text.append("\n转载说明：本文转载自上述原文地址，并遵守该协议的转载条件。");
        return text.toString();
    }

    public record Policy(
            String code,
            String name,
            String nameEn,
            String licenseUrl,
            boolean requiresApplication,
            String summary,
            String summaryEn,
            List<String> conditions,
            List<String> conditionsEn) {
    }
}
