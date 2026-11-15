package com.biliwind.blog.service;

import com.biliwind.blog.controller.FileTransferSignalResource;
import com.biliwind.blog.controller.McpPublicResource;
import com.biliwind.blog.controller.PublicToolsApiController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublicToolboxServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PublicToolboxService service = new PublicToolboxService();

    private PublicUrlService stubPublicUrl() {
        return new PublicUrlService() {
            @Override
            public String getBaseUrl() {
                return "https://www.geekcorelab.com";
            }
        };
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void calculatesIpv4AndIpv6Subnets() {
        Map<String, Object> v4 = service.calculateSubnet("192.168.1.10/24");
        assertEquals(4, v4.get("version"));
        assertEquals("192.168.1.0", v4.get("networkAddress"));
        assertEquals("192.168.1.255", v4.get("broadcastAddress"));
        assertEquals("255.255.255.0", v4.get("netmask"));
        assertEquals("0.0.0.255", v4.get("wildcardMask"));
        assertEquals(254L, v4.get("usableHosts"));

        Map<String, Object> v6 = service.calculateSubnet("2001:db8::1/64");
        assertEquals(6, v6.get("version"));
        assertEquals("2001:db8:0:0:0:0:0:0", v6.get("networkAddress"));
    }

    @Test
    void convertsTimestampsAndSimulatesCron() {
        Map<String, Object> ts = service.convertTimestamp("1700000000");
        assertEquals(1700000000L, ts.get("epochSeconds"));
        assertEquals("2023-11-14T22:13:20Z", ts.get("isoUtc"));

        Map<String, Object> cron = service.nextCronRuns("*/5 * * * *", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) cron.get("nextRuns");
        assertEquals(3, runs.size());
    }

    @Test
    void computesHashAndEstimatesTokens() {
        Map<String, Object> hash = service.computeHash("hello", "SHA-256");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash.get("hex"));

        Map<String, Object> tok = service.estimateTokens("Hello WindBlog 你好世界");
        assertEquals(4, tok.get("cjkCharacters"));
        assertTrue(((Integer) tok.get("estimatedTokens")) > 0);
    }

    @Test
    void publicToolsApiAndMcpEndpointsWorkWithoutDb() throws Exception {
        PublicToolsApiController api = new PublicToolsApiController();
        inject(api, "toolboxService", service);
        inject(api, "publicUrlService", stubPublicUrl());

        Response listResp = api.listTools();
        assertEquals(200, listResp.getStatus());
        JsonNode listJson = mapper.valueToTree(listResp.getEntity());
        assertTrue(listJson.path("tools").size() >= 16);
        assertEquals("https://www.geekcorelab.com/api/public/mcp", listJson.path("mcpEndpoint").asText());

        McpPublicResource mcp = new McpPublicResource();
        inject(mcp, "toolboxService", service);
        inject(mcp, "publicUrlService", stubPublicUrl());
        inject(mcp, "objectMapper", mapper);

        Response rpcList = mcp.handleRpc(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        assertEquals(200, rpcList.getStatus());
        JsonNode toolsList = mapper.valueToTree(rpcList.getEntity());
        assertEquals(6, toolsList.at("/result/tools").size());

        Response rpcCall = mcp.handleRpc(mapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"subnet_calc\",\"arguments\":{\"cidr\":\"10.0.0.5/24\"}}}"));
        assertEquals(200, rpcCall.getStatus());
        JsonNode callJson = mapper.valueToTree(rpcCall.getEntity());
        assertFalse(callJson.at("/result/isError").asBoolean());
        assertTrue(callJson.at("/result/content/0/text").asText().contains("10.0.0.0"));
    }

    @Test
    void easyModeSignalRelayExchangesOfferAndAnswer() throws Exception {
        FileTransferSignalResource signal = new FileTransferSignalResource();
        Response created = signal.createRoom(mapper.readTree("{\"offer\":\"OFFER_TOKEN_123\"}"));
        assertEquals(201, created.getStatus());
        JsonNode createdJson = mapper.valueToTree(created.getEntity());
        String roomId = createdJson.path("roomId").asText();
        String hostToken = createdJson.path("hostToken").asText();
        assertFalse(roomId.isBlank());
        assertFalse(hostToken.isBlank());

        Response offerResp = signal.getOffer(roomId);
        assertEquals(200, offerResp.getStatus());
        assertEquals("OFFER_TOKEN_123", mapper.valueToTree(offerResp.getEntity()).path("offer").asText());

        Response notReady = signal.pollAnswer(roomId, hostToken);
        assertEquals(200, notReady.getStatus());
        assertFalse(mapper.valueToTree(notReady.getEntity()).path("ready").asBoolean());

        Response wrongToken = signal.pollAnswer(roomId, "wrong");
        assertEquals(403, wrongToken.getStatus());

        Response submitAns = signal.submitAnswer(roomId, mapper.readTree("{\"answer\":\"ANSWER_TOKEN_456\"}"));
        assertEquals(200, submitAns.getStatus());

        Response ready = signal.pollAnswer(roomId, hostToken);
        assertEquals(200, ready.getStatus());
        JsonNode readyJson = mapper.valueToTree(ready.getEntity());
        assertTrue(readyJson.path("ready").asBoolean());
        assertEquals("ANSWER_TOKEN_456", readyJson.path("answer").asText());

        // Consumed room should no longer exist
        assertEquals(404, signal.getOffer(roomId).getStatus());
    }
}
