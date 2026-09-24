package com.biliwind.blog.service.storage;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumSet;
import java.util.List;

@ApplicationScoped
public class StorageRegionPolicy {

    public enum Placement { NORMAL, ENCRYPTED_BACKUP, SKIP }

    public Placement placement(Media media, StorageClassEntity target) {
        if (media == null || target == null || !Boolean.TRUE.equals(target.isEnabled)) {
            return Placement.SKIP;
        }
        if (contains(media.skipStorageClasses, target.name)) {
            return Placement.SKIP;
        }
        if (media.syncStorageClasses != null && !media.syncStorageClasses.isEmpty()
                && !Boolean.TRUE.equals(target.isPrimary)
                && !contains(media.syncStorageClasses, target.name)) {
            return Placement.SKIP;
        }
        EnumSet<BlogRegion> applicable = applicableRegions(media);
        if (target.excludedContentRegions != null) {
            for (String code : target.excludedContentRegions) {
                if ("global".equalsIgnoreCase(code)
                        || applicable.contains(BlogRegion.fromCode(code))) {
                    return Boolean.TRUE.equals(target.allowEncryptedBackup)
                            ? Placement.ENCRYPTED_BACKUP : Placement.SKIP;
                }
            }
        }
        return Placement.NORMAL;
    }

    EnumSet<BlogRegion> applicableRegions(Media media) {
        EnumSet<BlogRegion> regions = EnumSet.noneOf(BlogRegion.class);
        if (media.visibilityRegions == null || media.visibilityRegions.isEmpty()
                || contains(media.visibilityRegions, "global")) {
            regions = EnumSet.allOf(BlogRegion.class);
            regions.remove(BlogRegion.GLOBAL);
        } else {
            for (String code : media.visibilityRegions) {
                BlogRegion region = BlogRegion.fromCode(code);
                if (region != BlogRegion.GLOBAL) {
                    regions.add(region);
                }
            }
        }
        if (media.hiddenRegions != null) {
            for (String code : media.hiddenRegions) {
                if ("global".equalsIgnoreCase(code)) {
                    regions.clear();
                } else {
                    regions.remove(BlogRegion.fromCode(code));
                }
            }
        }
        return regions;
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || expected == null) {
            return false;
        }
        return values.stream().anyMatch(value -> expected.equalsIgnoreCase(value));
    }
}
