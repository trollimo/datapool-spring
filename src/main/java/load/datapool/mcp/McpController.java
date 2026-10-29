package load.datapool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** MCP-сервер (Streamable HTTP, JSON-RPC 2.0) для подключения ИИ-агентов: POST /mcp. */
@RestController
public class McpController {

    public static final String SERVER_VERSION = "3.1.1";
    static final String ABOUT_URI = "datapool://about";
    private static final String[] SUPPORTED = {"2025-06-18", "2025-03-26", "2024-11-05"};

    private final Logger logger = LoggerFactory.getLogger(McpController.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpTools tools;

    @Autowired
    public McpController(McpTools tools) {
        this.tools = tools;
    }

    /** Серверных уведомлений нет, поток SSE не открываем. */
    @GetMapping("/mcp")
    public ResponseEntity<String> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST").body("Use POST with a JSON-RPC 2.0 message");
    }

    @PostMapping(path = "/mcp", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<JsonNode> post(@RequestBody String body) {
        JsonNode in;
        try {
            in = mapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.ok(error(null, -32700, "Parse error"));
        }
        if (in == null || in.isMissingNode()) return ResponseEntity.ok(error(null, -32600, "Invalid Request"));
        if (in.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode m : in) {
                JsonNode r = handle(m);
                if (r != null) out.add(r);
            }
            return out.size() == 0 ? ResponseEntity.accepted().<JsonNode>build() : ResponseEntity.ok(out);
        }
        JsonNode r = handle(in);
        return r == null ? ResponseEntity.accepted().<JsonNode>build() : ResponseEntity.ok(r);
    }

    /** Один JSON-RPC запрос. null — для уведомлений ответа нет. */
    JsonNode handle(JsonNode msg) {
        if (!msg.isObject() || !msg.has("method")) return error(msg.get("id"), -32600, "Invalid Request");
        JsonNode id = msg.get("id");
        String method = msg.get("method").asText();
        boolean notification = id == null || id.isNull();
        try {
            switch (method) {
                case "initialize": return result(id, initialize(msg.path("params")));
                case "ping": return result(id, mapper.createObjectNode());
                case "tools/list": {
                    ObjectNode r = mapper.createObjectNode();
                    ArrayNode a = r.putArray("tools");
                    tools.definitions().forEach(a::add);
                    return result(id, r);
                }
                case "tools/call": return result(id, callTool(msg.path("params")));
                case "resources/list": {
                    ObjectNode r = mapper.createObjectNode();
                    ObjectNode res = r.putArray("resources").addObject();
                    res.put("uri", ABOUT_URI);
                    res.put("name", "about");
                    res.put("description", "How datapool works, troubleshooting, how not to harm running tests");
                    res.put("mimeType", "text/markdown");
                    return result(id, r);
                }
                case "resources/read": {
                    String uri = msg.path("params").path("uri").asText();
                    if (!ABOUT_URI.equals(uri)) return error(id, -32002, "Resource not found: " + uri);
                    ObjectNode r = mapper.createObjectNode();
                    ObjectNode c = r.putArray("contents").addObject();
                    c.put("uri", ABOUT_URI);
                    c.put("mimeType", "text/markdown");
                    c.put("text", McpGuide.ABOUT);
                    return result(id, r);
                }
                default:
                    if (notification) return null;   // notifications/initialized и подобные
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            logger.error("MCP error in " + method, e);
            return notification ? null : error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String version = SUPPORTED[0];
        for (String s : SUPPORTED) if (s.equals(requested)) version = s;
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", version);
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools").put("listChanged", false);
        caps.putObject("resources").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "datapool");
        info.put("version", SERVER_VERSION);
        r.put("instructions", McpGuide.ABOUT);
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        ObjectNode r = mapper.createObjectNode();
        ObjectNode c = r.putArray("content").addObject();
        c.put("type", "text");
        try {
            c.put("text", tools.call(name, params.get("arguments")));
            r.put("isError", false);
        } catch (McpTools.ToolError e) {
            c.put("text", e.getMessage());
            r.put("isError", true);
        } catch (Exception e) {
            logger.error("MCP tool " + name + " failed", e);
            c.put("text", "Tool failed: " + e.getMessage());
            r.put("isError", true);
        }
        return r;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id == null) r.putNull("id"); else r.set("id", id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r;
    }
}
