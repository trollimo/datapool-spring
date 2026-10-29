package load.datapool.mcp;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** Сводка по расхождениям без Spring. */
public class McpToolsStatisticsTest {

    private Map<String, Object> pool(String name, boolean diverged, int size, int max, int holes, int onlyDb, int onlyCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pool", name);
        m.put("diverged", diverged);
        m.put("lockerSize", size);
        m.put("dbMaxRid", max);
        m.put("holes", holes);
        m.put("lockedOnlyInDb", onlyDb);
        m.put("lockedOnlyInLocker", onlyCache);
        return m;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void summaryCountsBrokenPoolsAndReasons() {
        McpTools t = new McpTools(null, null, null, null, null);
        List<Map<String, Object>> all = Arrays.asList(
                pool("A.P1", true, 5, 10, 2, 1, 0),
                pool("A.P2", false, 4, 4, 1, 0, 0),
                pool("A.P3", true, 4, 4, 0, 0, 3));
        Map<String, Object> s = t.statistics(all);
        assertEquals(3, s.get("pools"));
        assertEquals(2, s.get("diverged"));
        assertEquals(3L, s.get("totalHoles"));
        List<Map<String, Object>> broken = (List<Map<String, Object>>) s.get("brokenPools");
        assertEquals("A.P1", broken.get(0).get("pool"));
        assertEquals(Arrays.asList("lockerSize 5 != dbMaxRid 10", "1 rows locked only in DB"), broken.get(0).get("reasons"));
        assertEquals(Arrays.asList("3 rows locked only in cache"), broken.get(1).get("reasons"));
        assertTrue(s.get("advice").toString().contains("repair_pools"));
    }

    @Test
    public void healthyPoolsGiveReassuringAdvice() {
        McpTools t = new McpTools(null, null, null, null, null);
        Map<String, Object> s = t.statistics(Collections.singletonList(pool("A.P", false, 1, 1, 0, 0, 0)));
        assertEquals(0, s.get("diverged"));
        assertEquals("All counters are consistent.", s.get("advice"));
    }

    @Test
    public void errorPoolIsReported() {
        McpTools t = new McpTools(null, null, null, null, null);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("pool", "A.X");
        bad.put("diverged", true);
        bad.put("error", "table not found");
        Map<String, Object> s = t.statistics(Collections.singletonList(bad));
        assertEquals(1, s.get("diverged"));
        assertTrue(s.get("brokenPools").toString().contains("error: table not found"));
    }
}
