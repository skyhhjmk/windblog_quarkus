package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Media;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class MediaAccessService {

    public boolean canAccess(Media media, BlogRegion currentRegion) {
        if (media == null) {
            return false;
        }

        BlogRegion safeRegion = currentRegion;
        if (safeRegion == null) {
            safeRegion = BlogRegion.GLOBAL;
        }

        String regionCode = safeRegion.getCode();
        if (containsRegion(media.hiddenRegions, regionCode)) {
            return false;
        }

        return RegionVisibility.isVisible(media.visibilityRegions, safeRegion);
    }

    private boolean containsRegion(List<String> regions, String expectedRegion) {
        if (regions == null || expectedRegion == null) {
            return false;
        }
        for (String region : regions) {
            if (region != null && expectedRegion.equalsIgnoreCase(region.trim())) {
                return true;
            }
        }
        return false;
    }
}
