package load.datapool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import load.datapool.AbstractSpringTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class McpTest extends AbstractSpringTest {

    private final ObjectMapper om = new ObjectMapper();
    private int id = 0;

    @Autowired McpTools tools;

    @Before
    public void relaxLimits() {
        setLimit(1000);
    }

    @After
    public void restoreLimits() {
        setLimit(30);
        ((Semaphore) ReflectionTestUtils.getField(tools, "heavy")).drainPermits();
        ((Semaphore) ReflectionTestUtils.getField(tools, "heavy")).release();
    }

    private void setLimit(int n) {
        ReflectionTestUtils.setField(tools, "maxCallsPerMinute", n);
        ReflectionTestUtils.setField(tools, "windowCalls", 0);
        ((AtomicLong) ReflectionTestUtils.getField(tools, "windowStart")).set(0);
    }

    private JsonNode rpc(String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        MvcResult r = mvc.perform(post("/mcp").contentType("application/json").content(body)).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return om.readTree(body(r));
    }

    /** Вызов тулы: возвращает result (content[0].text и isError). */
    private JsonNode call(String name, String args) throws Exception {
        JsonNode res = rpc("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}");
        assertNull(res.toString(), res.get("error"));
        return res.get("result");
    }

    private String text(JsonNode result) {
        return result.get("content").get(0).get("text").asText();
    }

    private void ok(JsonNode result) {
        assertFalse(text(result), result.get("isError").asBoolean());
    }

    private String args(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":").append(kv[i + 1]);
        }
        return sb.append('}').toString();
    }

    private String q(String s) {
        try { return om.writeValueAsString(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---- протокол ----

    @Test
    public void initializeReturnsInstructionsAndCapabilities() throws Exception {
        JsonNode r = rpc("initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}").get("result");
        assertEquals("2025-03-26", r.get("protocolVersion").asText());
        assertEquals("datapool", r.get("serverInfo").get("name").asText());
        assertEquals(McpController.SERVER_VERSION, r.get("serverInfo").get("version").asText());
        assertTrue(r.get("capabilities").has("tools"));
        assertTrue(r.get("instructions").asText().contains("do not harm running tests") || r.get("instructions").asText().contains("do not harm"));
    }

    @Test
    public void initializeFallsBackToSupportedVersion() throws Exception {
        JsonNode r = rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}").get("result");
        assertEquals("2025-06-18", r.get("protocolVersion").asText());
    }

    @Test
    public void notificationsGetNoBody() throws Exception {
        MvcResult r = mvc.perform(post("/mcp").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andReturn();
        assertEquals(202, r.getResponse().getStatus());
        assertEquals("", body(r));
    }

    @Test
    public void pingAndUnknownMethodAndParseError() throws Exception {
        assertTrue(rpc("ping", null).get("result").isObject());
        assertEquals(-32601, rpc("no/such", null).get("error").get("code").asInt());
        MvcResult r = mvc.perform(post("/mcp").contentType("application/json").content("{not json")).andReturn();
        assertEquals(-32700, om.readTree(body(r)).get("error").get("code").asInt());
    }

    @Test
    public void batchRequestsAreAnswered() throws Exception {
        MvcResult r = mvc.perform(post("/mcp").contentType("application/json")
                .content("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]")).andReturn();
        JsonNode a = om.readTree(body(r));
        assertEquals(2, a.size());
    }

    @Test
    public void getIsNotAllowed() throws Exception {
        assertEquals(405, mvc.perform(get("/mcp")).andReturn().getResponse().getStatus());
    }

    @Test
    public void toolsListHasMaintenanceToolsButNotTheHotPath() throws Exception {
        Set<String> names = new HashSet<>();
        for (JsonNode t : rpc("tools/list", null).get("result").get("tools")) {
            names.add(t.get("name").asText());
            assertEquals("object", t.get("inputSchema").get("type").asText());
            assertFalse(t.get("description").asText().isEmpty());
        }
        assertEquals(new HashSet<>(Arrays.asList("about", "list_pools", "counters_stats", "repair_pools", "reset_sequence",
                "unlock", "put_value", "upload_csv", "download_pool_csv", "drop_pool")), names);
        assertFalse(names.contains("get_next_value"));
        assertFalse(names.contains("search_by_key"));
    }

    @Test
    public void aboutResourceAndToolSayHowNotToHarmTests() throws Exception {
        JsonNode list = rpc("resources/list", null).get("result").get("resources");
        assertEquals("datapool://about", list.get(0).get("uri").asText());
        String about = rpc("resources/read", "{\"uri\":\"datapool://about\"}").get("result").get("contents").get(0).get("text").asText();
        assertEquals(about, text(call("about", "{}")));
        assertTrue(about.contains("Troubleshooting"));
        assertTrue(about.contains("Do NOT call tools in loops"));
        assertTrue(about.contains("get-next-value and search-by-key are deliberately NOT available"));
        assertEquals(-32002, rpc("resources/read", "{\"uri\":\"datapool://other\"}").get("error").get("code").asInt());
    }

    // ---- тулы ----

    @Test
    public void listPoolsAndCountersStats() throws Exception {
        fill(3);
        JsonNode pools = om.readTree(text(call("list_pools", "{}")));
        boolean found = false;
        for (JsonNode p : pools) if (p.get("pool").asText().equals("TST." + pool.toUpperCase())) { found = true; assertEquals(3, p.get("dbRows").asInt()); }
        assertTrue(found);

        jdbc.update("update tst." + pool + " set locked = true where rid = 2");   // расхождение: БД знает, кэш нет
        JsonNode broken = om.readTree(text(call("list_pools", args("only_broken", "true"))));
        assertTrue(broken.size() >= 1);
        for (JsonNode p : broken) assertTrue(p.get("diverged").asBoolean());

        JsonNode stats = om.readTree(text(call("counters_stats", "{}")));
        assertTrue(stats.get("diverged").asInt() >= 1);
        boolean mine = false;
        for (JsonNode b : stats.get("brokenPools")) {
            if (b.get("pool").asText().equals("TST." + pool.toUpperCase())) { mine = true; assertTrue(b.get("reasons").toString().contains("locked only in DB")); }
        }
        assertTrue(mine);
        assertTrue(stats.get("advice").asText().contains("repair_pools"));
    }

    @Test
    public void repairPoolsFixesDivergence() throws Exception {
        fill(4);
        jdbc.update("update tst." + pool + " set locked = true where rid = 2");
        ok(call("repair_pools", args("pools", "[" + q("TST." + pool.toUpperCase()) + "]")));
        JsonNode stats = om.readTree(text(call("counters_stats", "{}")));
        for (JsonNode b : stats.get("brokenPools")) assertNotEquals("TST." + pool.toUpperCase(), b.get("pool").asText());
        Set<Integer> got = new HashSet<>();
        for (int i = 0; i < 3; i++) got.add(rid(body(next(true))));
        assertEquals(new HashSet<>(Arrays.asList(1, 3, 4)), got);
    }

    @Test
    public void repairPoolsValidatesInput() throws Exception {
        assertTrue(call("repair_pools", args("pools", "[\"nodot\"]")).get("isError").asBoolean());
        assertTrue(call("repair_pools", args("pools", "[]")).get("isError").asBoolean());
        assertTrue(call("repair_pools", args("pools", "[\"A.B;drop table x\"]")).get("isError").asBoolean());
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) many.append(i > 0 ? "," : "").append("\"TST.P").append(i).append('"');
        assertTrue(text(call("repair_pools", args("pools", many + "]"))).contains("At most 20"));
    }

    @Test
    public void resetSequenceMovesCursorToStart() throws Exception {
        fill(3);
        next(false);
        next(false);
        ok(call("reset_sequence", args("env", q("tst"), "pool", q(pool))));
        assertEquals(1, rid(body(next(false))));
    }

    @Test
    public void unlockTool() throws Exception {
        fill(2);
        next(true);
        next(true);
        assertEquals(400, next(true).getResponse().getStatus());
        ok(call("unlock", args("env", q("tst"), "pool", q(pool), "rid", "1")));
        assertEquals(1, rid(body(next(true))));
        ok(call("unlock", args("env", q("tst"), "pool", q(pool), "unlock_all", "true")));
        assertEquals(200, next(true).getResponse().getStatus());
        // без параметров — ошибка
        assertTrue(call("unlock", args("env", q("tst"), "pool", q(pool))).get("isError").asBoolean());
    }

    @Test
    public void putValueTool() throws Exception {
        ok(call("put_value", args("env", q("tst"), "pool", q(pool), "value", q("{\"x\":\"1\"}"), "search_key", q("kk"))));
        assertTrue(body(next(false)).contains("\"x\":\"1\""));
        assertEquals(200, mvc.perform(get("/api/v1/search-by-key?env=tst&pool=" + pool + "&search-key=kk")).andReturn().getResponse().getStatus());
    }

    @Test
    public void uploadAndDownloadRoundTrip() throws Exception {
        ok(call("upload_csv", args("env", q("tst"), "pool", q(pool), "csv", q("a;b\n1;2\n3;4\n"), "delimiter", q(";"))));
        String csv = text(call("download_pool_csv", args("env", q("tst"), "pool", q(pool), "delimiter", q(";")))).replace("\r\n", "\n");
        assertEquals("a;b\n1;2\n3;4\n", csv);
        String other = freshName();
        ok(call("upload_csv", args("env", q("tst"), "pool", q(other), "csv", q(csv), "delimiter", q(";"))));
        assertEquals(text(call("download_pool_csv", args("env", q("tst"), "pool", q(pool)))), text(call("download_pool_csv", args("env", q("tst"), "pool", q(other)))));
    }

    @Test
    public void uploadOverrideRecreates() throws Exception {
        ok(call("upload_csv", args("env", q("tst"), "pool", q(pool), "csv", q("a\n1\n2\n3\n"))));
        ok(call("upload_csv", args("env", q("tst"), "pool", q(pool), "csv", q("a\n9\n"), "override", "true")));
        assertEquals(1, om.readTree(text(call("list_pools", "{}"))).findParents("pool").stream()
                .filter(p -> p.get("pool").asText().equals("TST." + pool.toUpperCase()) && p.get("dbRows").asInt() == 1).count());
    }

    @Test
    public void uploadTooLargeIsRefused() throws Exception {
        ReflectionTestUtils.setField(tools, "uploadMaxChars", 10);
        try {
            JsonNode r = call("upload_csv", args("env", q("tst"), "pool", q(pool), "csv", q("a\n1111111111111\n")));
            assertTrue(r.get("isError").asBoolean());
            assertTrue(text(r).contains("REST"));
        } finally {
            ReflectionTestUtils.setField(tools, "uploadMaxChars", 1000000);
        }
    }

    @Test
    public void downloadOfBigPoolIsRefused() throws Exception {
        fill(5);
        ReflectionTestUtils.setField(tools, "downloadMaxRows", 3);
        try {
            JsonNode r = call("download_pool_csv", args("env", q("tst"), "pool", q(pool)));
            assertTrue(r.get("isError").asBoolean());
            assertTrue(text(r).contains("limit"));
        } finally {
            ReflectionTestUtils.setField(tools, "downloadMaxRows", 5000);
        }
    }

    @Test
    public void downloadOfMissingPoolIsError() throws Exception {
        JsonNode r = call("download_pool_csv", args("env", q("tst"), "pool", q("nosuchpool")));
        assertTrue(r.get("isError").asBoolean());
    }

    @Test
    public void dropPoolNeedsConfirmation() throws Exception {
        fill(1);
        JsonNode refused = call("drop_pool", args("env", q("tst"), "pool", q(pool), "confirm", q("yes")));
        assertTrue(refused.get("isError").asBoolean());
        assertEquals("pool must survive without confirmation", 200, next(false).getResponse().getStatus());
        ok(call("drop_pool", args("env", q("tst"), "pool", q(pool), "confirm", q("TST." + pool))));
        assertEquals(400, next(false).getResponse().getStatus());
    }

    @Test
    public void badNamesAreRejectedBeforeTouchingTheDb() throws Exception {
        String[] bad = {"x; drop table y", "a.b", "a b", "", "x'--"};
        for (String b : bad) {
            assertTrue(b, call("reset_sequence", args("env", q("tst"), "pool", q(b))).get("isError").asBoolean());
            assertTrue(b, call("download_pool_csv", args("env", q(b), "pool", q("p"))).get("isError").asBoolean());
            assertTrue(b, call("drop_pool", args("env", q("tst"), "pool", q(b), "confirm", q("x"))).get("isError").asBoolean());
        }
    }

    @Test
    public void unknownToolIsToolError() throws Exception {
        assertTrue(call("get_next_value", "{}").get("isError").asBoolean());
    }

    // ---- защита сервиса от ИИ-агентов ----

    @Test
    public void callsAreRateLimited() throws Exception {
        setLimit(3);
        for (int i = 0; i < 3; i++) ok(call("about", "{}"));
        JsonNode r = call("about", "{}");
        assertTrue(r.get("isError").asBoolean());
        assertTrue(text(r).startsWith("rate limited"));
    }

    @Test
    public void rateLimitWindowResets() throws Exception {
        setLimit(1);
        ok(call("about", "{}"));
        assertTrue(call("about", "{}").get("isError").asBoolean());
        ((AtomicLong) ReflectionTestUtils.getField(tools, "windowStart")).set(System.currentTimeMillis() - 61_000);
        ok(call("about", "{}"));
    }

    @Test
    public void onlyOneHeavyOperationAtATime() throws Exception {
        Semaphore heavy = (Semaphore) ReflectionTestUtils.getField(tools, "heavy");
        assertTrue(heavy.tryAcquire());
        try {
            JsonNode r = call("upload_csv", args("env", q("tst"), "pool", q(pool), "csv", q("a\n1\n")));
            assertTrue(r.get("isError").asBoolean());
            assertTrue(text(r).startsWith("busy"));
            ok(call("list_pools", "{}"));   // лёгкие тулы работают
        } finally {
            heavy.release();
        }
        ok(call("upload_csv", args("env", q("tst"), "pool", q(pool), "csv", q("a\n1\n"))));
    }

    @Test
    public void heavyPermitIsReleasedAfterError() throws Exception {
        assertTrue(call("drop_pool", args("env", q("tst"), "pool", q(pool), "confirm", q("no"))).get("isError").asBoolean());
        assertEquals(1, ((Semaphore) ReflectionTestUtils.getField(tools, "heavy")).availablePermits());
    }
}
