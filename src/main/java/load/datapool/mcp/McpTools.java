package load.datapool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import load.datapool.db.H2Template;
import load.datapool.service.LockerService;
import load.datapool.service.PoolDiagnosticsService;
import load.datapool.todo.rest.CsvDownloadController;
import load.datapool.todo.rest.PoolsController;
import load.datapool.todo.rest.TodoRestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Тулы MCP. Под капотом вызываются те же методы контроллеров, что и у REST API. */
@Component
public class McpTools {

    /** Ошибка вызова тулы: уходит агенту как isError=true. */
    public static class ToolError extends RuntimeException {
        public ToolError(String message) { super(message); }
    }

    private interface Handler { String call(JsonNode args) throws Exception; }

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final TodoRestController todo;
    private final CsvDownloadController csv;
    private final PoolsController pools;
    private final PoolDiagnosticsService diagnostics;
    private final LockerService lockerService;
    private final H2Template jdbc;

    @Value("${datapool.mcp.max-calls-per-minute:30}")
    private int maxCallsPerMinute;
    @Value("${datapool.mcp.download.max-rows:5000}")
    private int downloadMaxRows;
    @Value("${datapool.mcp.upload.max-chars:1000000}")
    private int uploadMaxChars;

    private final Semaphore heavy = new Semaphore(1);
    private final AtomicLong windowStart = new AtomicLong();
    private int windowCalls = 0;

    private final Map<String, Handler> handlers = new LinkedHashMap<>();
    private final List<ObjectNode> definitions = new ArrayList<>();
    private final Set<String> heavyTools = new HashSet<>(Arrays.asList("repair_pools", "upload_csv", "download_pool_csv", "drop_pool"));

    @Autowired
    public McpTools(TodoRestController todo, CsvDownloadController csv, PoolsController pools,
                    PoolDiagnosticsService diagnostics, LockerService lockerService, H2Template jdbc) {
        this.todo = todo;
        this.csv = csv;
        this.pools = pools;
        this.diagnostics = diagnostics;
        this.lockerService = lockerService;
        this.jdbc = jdbc;
        register();
    }

    public List<ObjectNode> definitions() { return definitions; }

    public boolean exists(String name) { return handlers.containsKey(name); }

    /** Вызывает тулу с учётом лимита частоты и защиты от параллельных тяжёлых операций. */
    public String call(String name, JsonNode args) throws Exception {
        Handler h = handlers.get(name);
        if (h == null) throw new ToolError("Unknown tool: " + name);
        if (!allowCall()) {
            throw new ToolError("rate limited: more than " + maxCallsPerMinute + " tool calls per minute. Wait a minute before the next call. Do not retry in a loop: the service may be under load.");
        }
        boolean isHeavy = heavyTools.contains(name);
        if (isHeavy && !heavy.tryAcquire()) {
            throw new ToolError("busy: another heavy operation (upload, download, drop, repair) is running. Wait and try once later.");
        }
        try {
            return h.call(args == null ? mapper.createObjectNode() : args);
        } finally {
            if (isHeavy) heavy.release();
        }
    }

    private synchronized boolean allowCall() {
        long now = System.currentTimeMillis();
        if (now - windowStart.get() >= 60_000) {
            windowStart.set(now);
            windowCalls = 0;
        }
        return ++windowCalls <= maxCallsPerMinute;
    }

    // ---- регистрация тул ----

    private void register() {
        tool("about", "How the datapool works, how to troubleshoot it and how not to harm running load tests. Read it first.",
                schema(), a -> McpGuide.ABOUT);

        tool("list_pools", "Table of all pools with counters in the DB and in the application cache; diverged pools first. Read-only.",
                schema(prop("only_broken", "boolean", "Return only pools whose counters diverge (default false)")),
                a -> {
                    List<Map<String, Object>> all = pools.pools();
                    if (a.path("only_broken").asBoolean(false)) {
                        List<Map<String, Object>> broken = new ArrayList<>();
                        for (Map<String, Object> m : all) if (Boolean.TRUE.equals(m.get("diverged"))) broken.add(m);
                        all = broken;
                    }
                    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all);
                });

        tool("counters_stats", "Summary of counter health: number of pools, number of diverged pools and the reason for each. Read-only.",
                schema(), a -> mapper.writerWithDefaultPrettyPrinter().writeValueAsString(statistics(pools.pools())));

        tool("repair_pools", "Rebuild the locker cache of the given pools from the DB and move the cursor to the first free row. Changes data used by running tests: only on request or when diverged.",
                schema(arrayProp("pools", "Full pool names ENV.POOL, for example LOAD.P1"), required("pools")),
                a -> {
                    List<String> list = new ArrayList<>();
                    for (JsonNode n : a.path("pools")) {
                        String full = n.asText();
                        int i = full.indexOf('.');
                        if (i < 1) throw new ToolError("Pool must be ENV.POOL: " + full);
                        checkName("env", full.substring(0, i));
                        checkName("pool", full.substring(i + 1));
                        list.add(full);
                    }
                    if (list.isEmpty()) throw new ToolError("pools is empty");
                    if (list.size() > 20) throw new ToolError("At most 20 pools per call");
                    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pools.repairBatch(list));
                });

        tool("reset_sequence", "Set the read cursor of a pool to 1 (read from the start). Does not touch the locker cache.",
                schema(prop("env", "string", "Schema, e.g. load"), prop("pool", "string", "Pool name"), required("env", "pool")),
                a -> text(todo.resetseq(env(a), pool(a))));

        tool("unlock", "Release locked rows: one rid, by search key, or the whole pool (unlock_all).",
                schema(prop("env", "string", "Schema"), prop("pool", "string", "Pool name"), prop("rid", "integer", "Row number"),
                        prop("search_key", "string", "Search key"), prop("unlock_all", "boolean", "Release every row of the pool"), required("env", "pool")),
                a -> text(todo.unlockData(env(a), pool(a), a.has("rid") ? a.get("rid").asText() : "-1",
                        a.path("search_key").asText(""), a.path("unlock_all").asBoolean(false))));

        tool("put_value", "Insert one row (JSON object as a string). Creates the pool if it does not exist.",
                schema(prop("env", "string", "Schema"), prop("pool", "string", "Pool name"), prop("value", "string", "JSON object as a string"),
                        prop("search_key", "string", "Optional search key"), required("env", "pool", "value")),
                a -> text(todo.putData(env(a), pool(a), a.path("search_key").asText(""), a.get("value").asText())));

        tool("upload_csv", "Load CSV text into a pool: first line = headers, other lines become JSON objects of strings. override=true drops and recreates the pool.",
                schema(prop("env", "string", "Schema"), prop("pool", "string", "Pool name"), prop("csv", "string", "CSV text"),
                        prop("delimiter", "string", "Column delimiter, default ,"), prop("override", "boolean", "Drop and recreate the pool, default false"), required("env", "pool", "csv")),
                a -> {
                    String body = a.get("csv").asText();
                    if (body.length() > uploadMaxChars) throw new ToolError("csv is longer than " + uploadMaxChars + " characters; use REST /api/v1/upload-csv-as-json for big files");
                    return text(todo.uploadCSV(env(a), pool(a), a.path("delimiter").asText(","), a.path("override").asBoolean(false), true,
                            new TextFile(body)));
                });

        tool("download_pool_csv", "Pool content as CSV. With all_columns=false the result can be loaded back with upload_csv using the same delimiter. Limited to a small pool.",
                schema(prop("env", "string", "Schema"), prop("pool", "string", "Pool name"), prop("delimiter", "string", "Column delimiter, default ,"),
                        prop("all_columns", "boolean", "Add rid, searchkey, locked columns (not for reload), default false"), required("env", "pool")),
                a -> {
                    String env = env(a), pool = pool(a);
                    if (!lockerService.poolExist(env, pool)) throw new ToolError("Pool not found: " + env + "." + pool);
                    Long rows = jdbc.queryForObject("SELECT count(rid) FROM " + env + "." + pool, Long.class);
                    if (rows != null && rows > downloadMaxRows) {
                        throw new ToolError("Pool has " + rows + " rows, MCP download limit is " + downloadMaxRows + ". Use REST /api/v1/download/csv.");
                    }
                    ResponseEntity<byte[]> r = csv.downloadCsv(env, pool, a.path("delimiter").asText(","), a.path("all_columns").asBoolean(false), 0, 0);
                    if (!r.getStatusCode().is2xxSuccessful() || r.getBody() == null) throw new ToolError("Download failed: " + r.getStatusCode());
                    return new String(r.getBody(), StandardCharsets.UTF_8);
                });

        tool("drop_pool", "Delete a pool completely (table, sequences, metrics, cache). confirm must equal ENV.POOL. Irreversible.",
                schema(prop("env", "string", "Schema"), prop("pool", "string", "Pool name"), prop("confirm", "string", "Repeat ENV.POOL to confirm"), required("env", "pool", "confirm")),
                a -> {
                    String env = env(a), pool = pool(a);
                    if (!a.path("confirm").asText("").equalsIgnoreCase(env + "." + pool)) {
                        throw new ToolError("confirm must be " + env + "." + pool + ". Nothing was deleted.");
                    }
                    return text(todo.deletePool(env, pool));
                });
    }

    /** Сводка по расхождениям: сколько пулов, сколько расходится и почему. */
    public Map<String, Object> statistics(List<Map<String, Object>> all) {
        List<Map<String, Object>> broken = new ArrayList<>();
        long holes = 0;
        for (Map<String, Object> m : all) {
            Number h = (Number) m.get("holes");
            if (h != null) holes += h.longValue();
            if (!Boolean.TRUE.equals(m.get("diverged"))) continue;
            List<String> reasons = new ArrayList<>();
            if (m.get("error") != null) reasons.add("error: " + m.get("error"));
            if (m.get("lockerSize") != null && !m.get("lockerSize").equals(m.get("dbMaxRid"))) reasons.add("lockerSize " + m.get("lockerSize") + " != dbMaxRid " + m.get("dbMaxRid"));
            if (m.get("lockedOnlyInDb") instanceof Number && ((Number) m.get("lockedOnlyInDb")).intValue() > 0) reasons.add(m.get("lockedOnlyInDb") + " rows locked only in DB");
            if (m.get("lockedOnlyInLocker") instanceof Number && ((Number) m.get("lockedOnlyInLocker")).intValue() > 0) reasons.add(m.get("lockedOnlyInLocker") + " rows locked only in cache");
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("pool", m.get("pool"));
            b.put("reasons", reasons);
            broken.add(b);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pools", all.size());
        res.put("diverged", broken.size());
        res.put("totalHoles", holes);
        res.put("brokenPools", broken);
        res.put("advice", broken.isEmpty() ? "All counters are consistent." : "Run repair_pools for the pools listed in brokenPools.");
        return res;
    }

    // ---- вспомогательное ----

    private String env(JsonNode a) { return checkName("env", a.path("env").asText("")); }
    private String pool(JsonNode a) { return checkName("pool", a.path("pool").asText("")); }

    private String checkName(String what, String v) {
        if (!NAME.matcher(v).matches()) throw new ToolError("Bad " + what + " '" + v + "': only letters, digits and underscore are allowed");
        return v;
    }

    private String text(ResponseEntity<?> r) {
        Object body = r.getBody();
        String s = body == null ? "" : body.toString();
        if (!r.getStatusCode().is2xxSuccessful()) throw new ToolError("HTTP " + r.getStatusCodeValue() + ": " + s);
        return s;
    }

    private void tool(String name, String description, ObjectNode inputSchema, Handler h) {
        ObjectNode d = mapper.createObjectNode();
        d.put("name", name);
        d.put("description", description);
        d.set("inputSchema", inputSchema);
        definitions.add(d);
        handlers.put(name, h);
    }

    /** Собирает JSON Schema объекта из свойств и (необязательного) списка required. */
    private ObjectNode schema(Object... parts) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        for (Object p : parts) {
            if (p instanceof Map.Entry) {
                @SuppressWarnings("unchecked") Map.Entry<String, JsonNode> e = (Map.Entry<String, JsonNode>) p;
                props.set(e.getKey(), e.getValue());
            } else if (p instanceof ArrayNode) {
                s.set("required", (ArrayNode) p);
            }
        }
        return s;
    }

    private Map.Entry<String, JsonNode> prop(String name, String type, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        n.put("description", description);
        return new AbstractMap.SimpleEntry<String, JsonNode>(name, n);
    }

    private Map.Entry<String, JsonNode> arrayProp(String name, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "array");
        n.putObject("items").put("type", "string");
        n.put("description", description);
        return new AbstractMap.SimpleEntry<String, JsonNode>(name, n);
    }

    private ArrayNode required(String... names) {
        ArrayNode a = mapper.createArrayNode();
        for (String n : names) a.add(n);
        return a;
    }

    /** Текст CSV как загруженный файл для upload-csv-as-json. */
    static final class TextFile implements MultipartFile {
        private final byte[] bytes;
        TextFile(String text) { this.bytes = text.getBytes(StandardCharsets.UTF_8); }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "mcp.csv"; }
        @Override public String getContentType() { return "text/csv"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public Resource getResource() { return new ByteArrayResource(bytes); }
        @Override public void transferTo(File dest) throws IOException { throw new IOException("not supported"); }
    }
}
