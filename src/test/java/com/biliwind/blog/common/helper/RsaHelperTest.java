package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsaHelperTest {

    @Test
    void shouldReloadConfiguredKeyPairAndDecryptExistingTrackingText(@TempDir Path keyDirectory)
            throws Exception {
        RsaHelper firstInstance = newHelper(keyDirectory);
        firstInstance.init();

        String trackingData = "tracking-data-survives-restart";
        String encrypted = firstInstance.encrypt(trackingData);

        RsaHelper reloadedInstance = newHelper(keyDirectory);
        reloadedInstance.init();

        assertTrue(encrypted.startsWith("H1:"));
        assertEquals(trackingData, reloadedInstance.decrypt(encrypted));
        assertTrue(Files.isRegularFile(keyDirectory.resolve("private.key")));
        assertTrue(Files.isRegularFile(keyDirectory.resolve("public.key")));
    }

    private static RsaHelper newHelper(Path keyDirectory) throws Exception {
        RsaHelper helper = new RsaHelper();
        setField(helper, "keyDirectory", keyDirectory.toString());
        setField(helper, "configuredClusterPublicKey", Optional.empty());
        return helper;
    }

    private static void setField(RsaHelper helper, String name, Object value) throws Exception {
        Field field = RsaHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(helper, value);
    }
}
