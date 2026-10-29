package load.datapool.service;

import load.datapool.AbstractSpringTest;
import load.datapool.prometheus.Exporter;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Сборщик истории статистики: rps, время ответа, ошибки, занятость, кольцо истории, ответ API. */
public class StatisticsServiceTest extends AbstractSpringTest {

    @Autowired StatisticsService statistics;
    @Autowired Exporter exporter;

    private void requests(String env, String p, String method, String details, int n, long micros) {
        for (int i = 0; i < n; i++) {
            exporter.increaseRequests(env, p, method, details);
        }
        // суммарное время ответа n запросов: micros на запрос
        Instant start = Instant.now().minusNanos(micros * 1000);
        for (int i = 0; i < n; i++) exporter.increaseLatency(env, p, method, details, start);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Double>> mine(String full) {
        return (Map<String, List<Double>>) ((Map<String, Object>) statistics.series(60).get("pools")).get(full);
    }

    @Test
    public void ratesAreCountedPerIntervalAndErrorsSeparately() throws Exception {
        String env = "STX", p = "P" + System.nanoTime() % 100000;
        long t = System.currentTimeMillis();
        statistics.sample(t);                                  // первая точка только запоминает счётчики
        requests(env, p, "get-next-value", null, 90, 2000);   // 90 успешных, по 2 мс
        requests(env, p, "get-next-value", "Pool not found", 10, 2000);
        statistics.sample(t + 10_000);                         // 10 секунд

        Map<String, List<Double>> m = mine(env + "." + p);
        assertNotNull(m);
        int last = m.get("rps").size() - 1;
        assertEquals(10.0, m.get("rps").get(last), 0.01);      // 100 запросов / 10 с
        assertEquals(1.0, m.get("errors").get(last), 0.01);    // 10 ошибок / 10 с
        double lat = m.get("latencyMs").get(last);
        assertTrue("latency " + lat, lat >= 2.0 && lat < 100);  // не меньше заданных 2 мс, плюс накладные расходы
    }

    @Test
    public void successfulDetailsAreNotErrors() throws Exception {
        String env = "STY", p = "P" + System.nanoTime() % 100000;
        long t = System.currentTimeMillis();
        statistics.sample(t);
        requests(env, p, "download-csv", "File prepared", 5, 100);
        statistics.sample(t + 5_000);
        Map<String, List<Double>> m = mine(env + "." + p);
        int last = m.get("errors").size() - 1;
        assertEquals(0.0, m.get("errors").get(last), 0.0001);
        assertEquals(1.0, m.get("rps").get(last), 0.01);
    }

    @Test
    public void idlePoolHasNoLatencyButZeroRate() throws Exception {
        String env = "STZ", p = "P" + System.nanoTime() % 100000;
        long t = System.currentTimeMillis();
        requests(env, p, "get-next-value", null, 3, 100);
        statistics.sample(t);
        statistics.sample(t + 15_000);                         // запросов за интервал не было
        Map<String, List<Double>> m = mine(env + "." + p);
        int last = m.get("rps").size() - 1;
        assertEquals(0.0, m.get("rps").get(last), 0.0001);
        assertNull(m.get("latencyMs").get(last));
    }

    @Test
    public void lockedPercentComesFromTheLockerCache() throws Exception {
        fill(4);
        next(true);
        next(true);                                            // занято 2 из 4
        long t = System.currentTimeMillis();
        statistics.sample(t);
        statistics.sample(t + 15_000);
        Map<String, List<Double>> m = mine("TST." + pool.toUpperCase());
        int last = m.get("lockedPct").size() - 1;
        assertEquals(50.0, m.get("lockedPct").get(last), 0.01);
        assertEquals(4.0, m.get("size").get(last), 0.01);
    }

    @Test
    public void counterResetIsNotNegative() throws Exception {
        String env = "STR", p = "P" + System.nanoTime() % 100000;
        long t = System.currentTimeMillis();
        statistics.sample(t);
        requests(env, p, "get-next-value", null, 5, 10);
        statistics.sample(t + 5_000);
        exporter.removeMetrics(env, p);                        // gauge-метрики; счётчики остаются, поэтому разности не отрицательные
        statistics.sample(t + 10_000);
        Map<String, List<Double>> m = mine(env + "." + p);
        for (Double v : m.get("rps")) assertTrue(v == null || v >= 0);
    }

    @Test
    public void historyIsLimitedByTheWindow() throws Exception {
        ReflectionTestUtils.setField(statistics, "windowMinutes", 1);   // 60 с / 15 с = 4 точки
        try {
            long t = System.currentTimeMillis();
            for (int i = 0; i < 12; i++) statistics.sample(t + i * 15_000L);
            List<?> ts = (List<?>) statistics.series(1).get("timestamps");
            assertTrue("ring keeps at most 4 points, got " + ts.size(), ts.size() <= 4);
        } finally {
            ReflectionTestUtils.setField(statistics, "windowMinutes", 60);
        }
    }

    @Test
    public void apiReturnsSeriesAndAllowsCachingForTheInterval() throws Exception {
        long t = System.currentTimeMillis() + 3600_000L;       // точка «в будущем», чтобы попасть в окно запроса
        statistics.sample(t);
        statistics.sample(t + 15_000);
        org.springframework.test.web.servlet.MvcResult r = mvc.perform(get("/api/v1/statistics?minutes=120")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("max-age=15", r.getResponse().getHeader("Cache-Control"));
        String json = body(r);
        assertTrue(json, json.contains("\"intervalSec\":15"));
        assertTrue(json, json.contains("\"timestamps\":["));
        assertTrue(json, json.contains("\"pools\":{"));
    }

    @Test
    public void pagesHaveTheMenu() throws Exception {
        for (String page : new String[]{"/pools.html", "/statistics.html", "/index.html"}) {
            String html = body(mvc.perform(get(page)).andReturn());
            assertTrue(page, html.contains("href=\"/pools.html\"") && html.contains("href=\"/statistics.html\"") && html.contains("class=\"menu\""));
        }
    }

    @Test
    public void statisticsPageHasChartsFilterAndCache() throws Exception {
        String html = new String(mvc.perform(get("/statistics.html")).andReturn().getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        for (String s : new String[]{"id=\"c1\"", "id=\"c2\"", "id=\"c3\"", "id=\"c4\"", "id=\"c5\"", "id=\"tbl\"", "api/v1/statistics", "CACHE_MS=15000", "sessionStorage",
                "id=\"total\" checked", "id=\"occupied\"", "Вредные циклы", "Итого"}) {
            assertTrue(s, html.contains(s));
        }
    }

    @Test
    public void cyclingIsCountedAsRetriesPerSecondAndHarmfulLoopsPerInterval() throws Exception {
        String env = "STL", p = "P" + System.nanoTime() % 100000;
        long t = System.currentTimeMillis();
        statistics.sample(t);
        requests(env, p, "get-next-value", null, 50, 100);
        exporter.increaseRetries(env, p, 30);                                   // 30 повторов за 10 с
        requests(env, p, "get-next-value", "Retry limit exceeded", 2, 100);     // 2 цикла, исчерпавших лимит
        statistics.sample(t + 10_000);
        Map<String, List<Double>> m = mine(env + "." + p);
        int last = m.get("loops").size() - 1;
        assertEquals(3.0, m.get("loops").get(last), 0.01);
        assertEquals(2.0, m.get("loopFails").get(last), 0.01);
        assertEquals(0.2, m.get("errors").get(last), 0.01);                     // это и ошибка запроса
    }

    @Test
    public void legitimateEmptyPoolIsNotHarmfulLoop() throws Exception {
        String env = "STE", p = "P" + System.nanoTime() % 100000;
        long t = System.currentTimeMillis();
        statistics.sample(t);
        requests(env, p, "get-next-value", "Empty pool", 4, 100);
        requests(env, p, "get-next-value", "Sequence does not reset", 3, 100);
        statistics.sample(t + 10_000);
        Map<String, List<Double>> m = mine(env + "." + p);
        assertEquals(0.0, m.get("loopFails").get(m.get("loopFails").size() - 1), 0.0001);
    }

    @Test
    public void realRetriesAppearInMetricsWhenTheCursorMeetsLockedRows() throws Exception {
        fill(3);
        assertEquals(1, rid(body(next(true))));                                 // строка 1 занята
        mvc.perform(get("/api/v1/resetseq?env=tst&pool=" + pool)).andReturn();  // курсор снова на 1
        assertEquals(2, rid(body(next(true))));                                 // 1 занята: повтор и сдвиг курсора
        String metrics = body(mvc.perform(get("/metrics")).andReturn());
        assertTrue(metrics, metrics.contains("datapool_get_next_retries{host=") && metrics.contains("name=\"" + pool.toUpperCase() + "\",} 1.0"));
    }
}
