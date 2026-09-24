package com.biliwind.blog.service.storage;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageRegionPolicyTest {
    private final StorageRegionPolicy policy = new StorageRegionPolicy();

    @Test
    void excludedAudienceRegionSelectsEncryptedBackup() {
        Media media = new Media();
        media.visibilityRegions = List.of("cn", "us");
        StorageClassEntity target = new StorageClassEntity();
        target.name = "oss-a";
        target.isEnabled = true;
        target.excludedContentRegions = List.of("us");
        target.allowEncryptedBackup = true;

        assertEquals(StorageRegionPolicy.Placement.ENCRYPTED_BACKUP,
                policy.placement(media, target));
        target.allowEncryptedBackup = false;
        assertEquals(StorageRegionPolicy.Placement.SKIP, policy.placement(media, target));
    }

    @Test
    void hiddenRegionDoesNotExcludeGlobalFile() {
        Media media = new Media();
        media.hiddenRegions = List.of("us");
        StorageClassEntity target = new StorageClassEntity();
        target.name = "oss-b";
        target.isEnabled = true;
        target.excludedContentRegions = List.of("us");
        assertEquals(StorageRegionPolicy.Placement.NORMAL, policy.placement(media, target));
        media.hiddenRegions = null;
        assertEquals(StorageRegionPolicy.Placement.SKIP, policy.placement(media, target));
    }
}
