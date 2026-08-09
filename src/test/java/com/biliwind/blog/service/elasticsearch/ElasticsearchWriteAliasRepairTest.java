package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchWriteAliasRepairTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSelectNewestIndexWhenAliasHasMultipleWriteIndices() throws Exception {
        String response = """
                {
                  "windblog-logs-000001": {"aliases": {"windblog-logs": {"is_write_index": true}}},
                  "windblog-logs-000013": {"aliases": {"windblog-logs": {"is_write_index": true}}}
                }
                """;

        ElasticsearchWriteAliasRepair.AliasRepairPlan plan = ElasticsearchWriteAliasRepair.plan(
                objectMapper,
                response,
                "windblog-logs",
                "windblog-logs-000001"
        );

        assertEquals("windblog-logs-000013", plan.selectedWriteIndex());
        assertTrue(plan.repairRequired());

        String requestBody = ElasticsearchWriteAliasRepair.buildRequestBody(
                objectMapper,
                plan,
                "windblog-logs"
        );
        assertTrue(requestBody.contains("\"index\":\"windblog-logs-000001\""));
        assertTrue(requestBody.contains("\"index\":\"windblog-logs-000013\""));
        assertTrue(requestBody.contains("\"is_write_index\":false"));
        assertTrue(requestBody.contains("\"is_write_index\":true"));
    }

    @Test
    void shouldRepairSingleAliasWithoutExplicitWriteFlag() throws Exception {
        String response = """
                {
                  "windblog-logs-000001": {"aliases": {"windblog-logs": {}}}
                }
                """;

        ElasticsearchWriteAliasRepair.AliasRepairPlan plan = ElasticsearchWriteAliasRepair.plan(
                objectMapper,
                response,
                "windblog-logs",
                "windblog-logs-000001"
        );

        assertTrue(plan.repairRequired());
        String requestBody = ElasticsearchWriteAliasRepair.buildRequestBody(
                objectMapper,
                plan,
                "windblog-logs"
        );
        assertTrue(requestBody.contains("\"is_write_index\":true"));
    }

    @Test
    void shouldLeaveHealthyAliasUnchanged() throws Exception {
        String response = """
                {
                  "windblog-logs-000013": {"aliases": {"windblog-logs": {"is_write_index": true}}}
                }
                """;

        ElasticsearchWriteAliasRepair.AliasRepairPlan plan = ElasticsearchWriteAliasRepair.plan(
                objectMapper,
                response,
                "windblog-logs",
                "windblog-logs-000001"
        );

        assertFalse(plan.repairRequired());
        assertEquals("windblog-logs-000013", plan.selectedWriteIndex());
    }
}
