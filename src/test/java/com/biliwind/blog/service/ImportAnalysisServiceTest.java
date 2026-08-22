package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportAnalysisServiceTest {
    @Test
    void ignoresBareProseCapturedFromHrefOrMarkdown() throws Exception {
        Class<?> accumulatorType = Class.forName(
                "com.biliwind.blog.service.ImportAnalysisService$RelativeUrlAccumulator");
        Constructor<?> constructor = accumulatorType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object accumulator = constructor.newInstance();
        Method scanText = ImportAnalysisService.class.getDeclaredMethod(
                "scanText", String.class, String.class, String.class, accumulatorType);
        scanText.setAccessible(true);
        scanText.invoke(new ImportAnalysisService(),
                "<a href=\"处理CSS的PHP文件\">说明</a>", "links", "description", accumulator);
        Method toMap = accumulatorType.getDeclaredMethod("toMap");
        toMap.setAccessible(true);
        Map<?, ?> report = (Map<?, ?>) toMap.invoke(accumulator);
        assertEquals(0, report.get("total"));
    }

    @Test
    void ignoresProtocolRelativeTextAtCommentLineStartButKeepsAssetAttributes() throws Exception {
        Class<?> accumulatorType = Class.forName(
                "com.biliwind.blog.service.ImportAnalysisService$RelativeUrlAccumulator");
        Constructor<?> constructor = accumulatorType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object accumulator = constructor.newInstance();
        Method scanText = ImportAnalysisService.class.getDeclaredMethod(
                "scanText", String.class, String.class, String.class, accumulatorType);
        scanText.setAccessible(true);
        ImportAnalysisService service = new ImportAnalysisService();
        scanText.invoke(service, "//keyserver.ubuntu.com", "post", "content", accumulator);
        scanText.invoke(service, "<img src=\"//cdn.example.com/a.png\">", "post", "content", accumulator);
        Method toMap = accumulatorType.getDeclaredMethod("toMap");
        toMap.setAccessible(true);
        Map<?, ?> report = (Map<?, ?>) toMap.invoke(accumulator);
        assertEquals(1, report.get("total"));
    }
}
