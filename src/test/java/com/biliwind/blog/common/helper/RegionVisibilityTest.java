package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.model.BlogRegion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionVisibilityTest {

    private static final List<List<String>> STORED_REGIONS = List.of(
            List.of(),
            List.of("global"),
            List.of("cn"),
            List.of("us"),
            List.of("cn", "us"),
            List.of("jp"),
            List.of("CN"),
            List.of("kr"),
            List.of("cn", "global"));

    private static final List<BlogRegion> CURRENT_REGIONS = List.of(BlogRegion.values());

    @Test
    void jpqlNativeSqlAndElasticsearchAgreeWithTheRule() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (List<String> stored : withNull(STORED_REGIONS)) {
            String serialized = storageJson(stored);
            for (BlogRegion currentRegion : CURRENT_REGIONS) {
                boolean expected = RegionVisibility.isVisible(stored, currentRegion);
                String regionCode = currentRegion.getCode();

                assertEquals(expected, evaluateSql(
                        RegionVisibility.jpqlFilter(currentRegion).fragment(), serialized, regionCode),
                        "named JPQL: stored=" + stored + ", region=" + regionCode);
                assertEquals(expected, evaluateSql(
                        RegionVisibility.jpqlFilter(2, currentRegion).fragment(), serialized, regionCode),
                        "positional JPQL: stored=" + stored + ", region=" + regionCode);
                assertEquals(expected, evaluateSql(
                        RegionVisibility.nativeSqlFilter("p.visibility_regions", currentRegion).fragment(),
                        serialized, regionCode),
                        "native SQL: stored=" + stored + ", region=" + regionCode);
                assertEquals(expected, evaluateElasticsearch(
                        mapper.readTree(RegionVisibility.elasticsearchFilter(currentRegion)), stored, regionCode),
                        "Elasticsearch DSL: stored=" + stored + ", region=" + regionCode);
            }
        }
    }

    @Test
    void shouldTreatEmptyAndMissingRegionsAsVisibleEverywhere() {
        for (BlogRegion region : CURRENT_REGIONS) {
            assertTrue(RegionVisibility.isVisible(null, region));
            assertTrue(RegionVisibility.isVisible(List.of(), region));
            assertTrue(RegionVisibility.isVisible(List.of("global"), region));
        }
    }

    @Test
    void shouldReadStoredCodesStrictlyLowercase() {
        assertTrue(RegionVisibility.isVisible(List.of("cn"), BlogRegion.CN));
        assertFalse(RegionVisibility.isVisible(List.of("CN"), BlogRegion.CN));
        assertFalse(RegionVisibility.isVisible(List.of(" cn "), BlogRegion.CN));
    }

    @Test
    void shouldNormalizeAndValidateOnWrite() {
        assertEquals(List.of("cn"), RegionVisibility.normalizeForWrite(List.of(" CN ")));
        assertEquals(List.of("cn"), RegionVisibility.normalizeForWrite(List.of("china")));
        assertEquals(List.of("cn", "us"), RegionVisibility.normalizeForWrite(List.of("cn", "US", "cn")));
        assertNull(RegionVisibility.normalizeForWrite(List.of("global", "cn")));
        assertNull(RegionVisibility.normalizeForWrite(List.of("   ", "")));
        assertThrows(BadRequestException.class, () -> RegionVisibility.normalizeForWrite(List.of("kr")));
        assertThrows(BadRequestException.class,
                () -> RegionVisibility.normalizeForWrite(List.of("jp", "invalid_unknown_code")));
    }

    private static List<List<String>> withNull(List<List<String>> values) {
        java.util.ArrayList<List<String>> result = new java.util.ArrayList<>();
        result.add(null);
        result.addAll(values);
        return result;
    }

    private static String storageJson(List<String> stored) {
        if (stored == null) {
            return null;
        }
        return stored.stream().collect(Collectors.joining("\",\"", "[\"", "\"]"))
                .replace("[\"\"]", "[]");
    }

    private static boolean evaluateSql(String fragment, String serialized, String regionCode) {
        String body = fragment.substring(1, fragment.length() - 1);
        for (String clause : body.split(" or ")) {
            if (clause.contains("is null") && serialized == null) {
                return true;
            }
            if (clause.contains("= '[]'") && "[]".equals(serialized)) {
                return true;
            }
            if (clause.contains("like '%\"global\"%'")
                    && serialized != null && serialized.contains("\"global\"")) {
                return true;
            }
            if ((clause.contains(":regionPattern") || clause.matches(".*\\?\\d+.*"))
                    && serialized != null && serialized.contains("\"" + regionCode + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateElasticsearch(JsonNode query, List<String> stored, String regionCode) {
        for (JsonNode clause : query.path("bool").path("should")) {
            if (clause.has("bool")) {
                if (stored == null || stored.isEmpty()) {
                    return true;
                }
                continue;
            }
            String term = clause.path("term").path("visibilityRegions").asText(null);
            if (term != null && stored != null
                    && (term.equals(regionCode) || term.equals("global"))
                    && stored.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
