package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodexCreatorDraftServiceTest {
    @Test
    void verificationSelectionChangesDraftIdentity() {
        String plain = CodexCreatorDraftService.requestKey(1L, 2L, "zh-cn", "instructions", false, List.of());
        String verified = CodexCreatorDraftService.requestKey(1L, 2L, "zh-cn", "instructions", true, List.of(3L));
        assertNotEquals(plain, verified);
        assertNotEquals(verified, CodexCreatorDraftService.requestKey(1L, 2L, "zh-cn", "instructions", true, List.of(4L)));
        assertEquals(CodexCreatorDraftService.requestKey(1L, 2L, "zh-cn", "instructions", true, List.of(4L, 3L)),
                CodexCreatorDraftService.requestKey(1L, 2L, "zh-cn", "instructions", true, List.of(3L, 4L, 3L)));
    }
}
