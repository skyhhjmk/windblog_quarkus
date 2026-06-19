package com.biliwind.blog.common.helper;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class MediaSecurityHelperTest {

    @Test
    void shouldDetectPrivateOrLoopbackUrls() {
        // 环回地址/内网 IP
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://127.0.0.1/abc"));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("https://localhost:8080/path"));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://10.0.0.1"));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://192.168.1.1"));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://172.16.0.1"));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://0.0.0.0/test"));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://[::1]/abc"));

        // 空值或非合法 URL
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress(null));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress(""));
        assertTrue(MediaSecurityHelper.isPrivateOrLoopbackAddress("not-a-valid-url"));

        // 外网合法 IP/域名
        assertFalse(MediaSecurityHelper.isPrivateOrLoopbackAddress("https://www.baidu.com/img"));
        assertFalse(MediaSecurityHelper.isPrivateOrLoopbackAddress("http://8.8.8.8/"));
    }
}
