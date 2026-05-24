package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.AffiliateLink;
import com.biliwind.blog.model.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;

/**
 * 将文章内登记过的商业链接替换成 go 链接。
 */
@ApplicationScoped
public class AffiliateContentRenderService {

    @Inject
    AffiliateTokenService affiliateTokenService;

    @Inject
    RepostLicenseService repostLicenseService;

    public String rewriteCommercialLinks(Post post, String html) {
        if (post == null) {
            return html;
        }
        if (html == null || html.isBlank()) {
            return html;
        }

        List<AffiliateLink> affiliateLinks = AffiliateLink.list("status = 1");
        if (affiliateLinks == null || affiliateLinks.isEmpty()) {
            return html;
        }

        Document document = Jsoup.parseBodyFragment(html);
        Elements anchors = document.select("a[href]");
        for (Element anchor : anchors) {
            String href = anchor.attr("href");
            AffiliateLink affiliateLink = findMatchingAffiliateLink(affiliateLinks, href);
            if (affiliateLink == null) {
                continue;
            }

            AffiliateTokenService.TokenCreationResult tokenCreationResult =
                    affiliateTokenService.createFirstPartyToken(post, affiliateLink);
            if (tokenCreationResult.rawToken == null) {
                continue;
            }

            anchor.attr("href", repostLicenseService.buildGoUrl(tokenCreationResult.rawToken));
            anchor.attr("rel", "sponsored nofollow");
        }

        return document.body().html();
    }

    private AffiliateLink findMatchingAffiliateLink(List<AffiliateLink> affiliateLinks, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }

        for (AffiliateLink affiliateLink : affiliateLinks) {
            if (affiliateLink.targetUrl == null) {
                continue;
            }
            if (href.equals(affiliateLink.targetUrl)) {
                return affiliateLink;
            }
        }
        return null;
    }
}
