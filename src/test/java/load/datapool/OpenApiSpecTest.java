package load.datapool;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.util.*;

import static org.junit.Assert.*;

/** Спецификация api-specification/openapi.yaml должна совпадать с реальными эндпоинтами. */
public class OpenApiSpecTest extends AbstractSpringTest {

    @Autowired RequestMappingHandlerMapping mapping;

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec() throws Exception {
        try (FileInputStream in = new FileInputStream("api-specification/openapi.yaml")) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void specIsOpenApi3() throws Exception {
        Map<String, Object> s = spec();
        assertTrue(s.get("openapi").toString().startsWith("3.0"));
        assertEquals("2.0.0", ((Map<String, Object>) s.get("info")).get("version"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void everyControllerEndpointIsDocumentedWithItsHttpMethod() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) spec().get("paths");
        Set<String> documented = new TreeSet<>();
        for (Map.Entry<String, Object> p : paths.entrySet()) {
            for (String method : ((Map<String, Object>) p.getValue()).keySet()) documented.add(method.toUpperCase() + " " + p.getKey());
        }
        Set<String> real = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            String pkg = e.getValue().getBeanType().getPackage().getName();
            if (!pkg.startsWith("load.datapool")) continue;
            for (String url : e.getKey().getPatternsCondition().getPatterns()) {
                if (url.equals("/") || url.equals("/about")) continue;   // страница документации
                String path = url.startsWith("/") ? url : "/" + url;
                for (org.springframework.web.bind.annotation.RequestMethod m : e.getKey().getMethodsCondition().getMethods()) real.add(m + " " + path);
            }
        }
        assertEquals("spec and code differ", real, documented);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void everyOperationHasResponsesAndTags() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) spec().get("paths");
        for (Map.Entry<String, Object> p : paths.entrySet()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) p.getValue()).entrySet()) {
                Map<String, Object> o = (Map<String, Object>) op.getValue();
                assertNotNull(p.getKey() + " " + op.getKey(), o.get("responses"));
                assertNotNull(p.getKey() + " " + op.getKey(), o.get("tags"));
                assertNotNull(p.getKey() + " " + op.getKey(), o.get("summary"));
            }
        }
    }
}
