package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a safe, atomic repair request for an Elasticsearch rollover write alias.
 * Historical indices are retained; only the write flag is reconciled.
 */
public final class ElasticsearchWriteAliasRepair {

    private static final Pattern ROLLOVER_INDEX_PATTERN = Pattern.compile("^windblog-logs-(\\d+)$");

    private ElasticsearchWriteAliasRepair() {
    }

    public static AliasRepairPlan plan(
            ObjectMapper objectMapper,
            String aliasResponse,
            String aliasName,
            String initialIndex
    ) throws IOException {
        JsonNode root = objectMapper.readTree(aliasResponse);
        if (root == null || !root.isObject()) {
            throw new IOException("Elasticsearch 别名响应不是 JSON 对象");
        }

        List<AliasIndex> indices = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode aliasNode = field.getValue().path("aliases").path(aliasName);
            if (aliasNode.isObject()) {
                indices.add(new AliasIndex(
                        field.getKey(),
                        aliasNode.path("is_write_index").asBoolean(false)
                ));
            }
        }

        if (indices.isEmpty()) {
            return new AliasRepairPlan(initialIndex, List.of(), true);
        }

        indices.sort(Comparator.comparing(AliasIndex::indexName, ElasticsearchWriteAliasRepair::compareIndexNames));
        String selectedWriteIndex = indices.get(indices.size() - 1).indexName();
        int writeIndexCount = 0;
        boolean selectedAlreadyWriteIndex = false;
        for (AliasIndex index : indices) {
            if (index.writeIndex()) {
                writeIndexCount++;
                if (selectedWriteIndex.equals(index.indexName())) {
                    selectedAlreadyWriteIndex = true;
                }
            }
        }

        boolean repairRequired = writeIndexCount != 1 || !selectedAlreadyWriteIndex;
        return new AliasRepairPlan(selectedWriteIndex, List.copyOf(indices), repairRequired);
    }

    public static String buildRequestBody(
            ObjectMapper objectMapper,
            AliasRepairPlan plan,
            String aliasName
    ) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode actions = body.putArray("actions");

        if (plan.indices().isEmpty()) {
            addWriteFlagAction(actions, plan.selectedWriteIndex(), aliasName, true);
            return objectMapper.writeValueAsString(body);
        }

        for (AliasIndex index : plan.indices()) {
            boolean isSelectedWriteIndex = plan.selectedWriteIndex().equals(index.indexName());
            addWriteFlagAction(actions, index.indexName(), aliasName, isSelectedWriteIndex);
        }
        return objectMapper.writeValueAsString(body);
    }

    private static void addWriteFlagAction(
            ArrayNode actions,
            String indexName,
            String aliasName,
            boolean isWriteIndex
    ) {
        ObjectNode addAction = actions.addObject().putObject("add");
        addAction.put("index", indexName);
        addAction.put("alias", aliasName);
        addAction.put("is_write_index", isWriteIndex);
    }

    private static int compareIndexNames(String first, String second) {
        Matcher firstMatcher = ROLLOVER_INDEX_PATTERN.matcher(first);
        Matcher secondMatcher = ROLLOVER_INDEX_PATTERN.matcher(second);
        if (firstMatcher.matches() && secondMatcher.matches()) {
            BigInteger firstNumber = new BigInteger(firstMatcher.group(1));
            BigInteger secondNumber = new BigInteger(secondMatcher.group(1));
            return firstNumber.compareTo(secondNumber);
        }
        if (firstMatcher.matches()) {
            return 1;
        }
        if (secondMatcher.matches()) {
            return -1;
        }
        return first.compareTo(second);
    }

    public record AliasIndex(String indexName, boolean writeIndex) {
    }

    public record AliasRepairPlan(
            String selectedWriteIndex,
            List<AliasIndex> indices,
            boolean repairRequired
    ) {
    }
}
