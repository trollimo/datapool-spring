package load.datapool.todo.rest;

import load.datapool.AbstractSpringTest;
import load.datapool.prometheus.Exporter;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Пулы, где около 30% строк занято навсегда: чтение не должно зацикливаться бесконечно.
 * «Вредный» цикл — get-next-value исчерпал лимит попыток и не нашёл данные.
 */
public class LockedRowsLoopTest extends AbstractSpringTest {

    private static final int ROWS = 300;
    @Autowired Exporter exporter;

    private String poolName(int i) { return pool + "x" + i; }

    private MvcResult next(String p, boolean locked) throws Exception {
        return mvc.perform(get("/api/v1/get-next-value?env=tst&pool=" + p + "&locked=" + locked)).andReturn();
    }

    /** Заполняет пул и занимает ~30% строк через API (берём все и возвращаем случайные 70%). Возвращает занятые rid. */
    private Set<Integer> lockThirtyPercent(String p, long seed) throws Exception {
        fill(p, ROWS);
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) all.add(rid(body(next(p, true))));
        assertEquals(ROWS, new HashSet<>(all).size());
        Random rnd = new Random(seed);
        Set<Integer> kept = new TreeSet<>();
        for (int rid : all) {
            if (rnd.nextInt(100) >= 30) mvc.perform(post("/api/v1/unlock?env=tst&pool=" + p + "&rid=" + rid)).andReturn();
            else kept.add(rid);
        }
        mvc.perform(get("/api/v1/resetseq?env=tst&pool=" + p)).andReturn();
        assertTrue("about 30%: " + kept.size(), kept.size() > ROWS * 0.2 && kept.size() < ROWS * 0.4);
        return kept;
    }

    private long harmfulLoops(String p) {
        Exporter.Totals t = exporter.requestTotals().get("TST." + p.toUpperCase());
        return t == null ? 0 : t.loopFails;
    }

    @Test
    public void concurrentReadsOfSeveralPoolsWithThirtyPercentLockedFinishAndSkipLockedRows() throws Exception {
        int pools = 4;
        Map<String, Set<Integer>> locked = new LinkedHashMap<>();
        for (int i = 0; i < pools; i++) locked.put(poolName(i), lockThirtyPercent(poolName(i), 7 + i));

        ExecutorService ex = Executors.newFixedThreadPool(12);
        List<Future<String>> fs = new ArrayList<>();
        for (String p : locked.keySet()) {
            for (int t = 0; t < 3; t++) {
                fs.add(ex.submit(() -> {
                    for (int i = 0; i < 400; i++) {
                        MvcResult r = next(p, false);
                        if (r.getResponse().getStatus() != 200) return p + ": HTTP " + r.getResponse().getStatus() + " " + body(r);
                        int rid = rid(body(r));
                        if (locked.get(p).contains(rid)) return p + ": issued locked rid " + rid;
                    }
                    return null;
                }));
            }
        }
        for (Future<String> f : fs) assertNull(f.get(90, TimeUnit.SECONDS));
        ex.shutdown();
        for (String p : locked.keySet()) assertEquals("harmful loops in " + p, 0, harmfulLoops(p));
        // цикл всё же работал: курсору приходилось обходить занятые строки
        Exporter.Totals t = exporter.requestTotals().get("TST." + poolName(0).toUpperCase());
        assertTrue("retries " + t.retries, t.retries > 0);
    }

    @Test
    public void lockingReadersTakeExactlyTheFreeRowsAndThenGetCleanEmptyAnswers() throws Exception {
        String p = poolName(9);
        Set<Integer> lockedForever = lockThirtyPercent(p, 99);
        int free = ROWS - lockedForever.size();

        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < ROWS + 50; i++) {
            fs.add(ex.submit(() -> {
                MvcResult r = next(p, true);
                return r.getResponse().getStatus() == 200 ? rid(body(r)) : -r.getResponse().getStatus();
            }));
        }
        Set<Integer> got = new HashSet<>();
        int refused = 0;
        for (Future<Integer> f : fs) {
            int v = f.get(90, TimeUnit.SECONDS);
            if (v > 0) { assertTrue("duplicate " + v, got.add(v)); assertFalse(lockedForever.contains(v)); }
            else { assertEquals(-400, v); refused++; }
        }
        ex.shutdown();
        assertEquals(free, got.size());
        assertEquals(ROWS + 50 - free, refused);
        assertEquals("exhausted pool is not a harmful loop", 0, harmfulLoops(p));
    }

    @Test
    public void cacheThinksRowsAreFreeButDbHasThemLockedIsFiniteAndCountedAsHarmful() throws Exception {
        String p = poolName(5);
        fill(p, 40);
        jdbc.update("update tst." + p + " set locked = true");              // в БД всё занято, кэш об этом не знает
        long before = System.nanoTime();
        MvcResult r = next(p, false);
        long ms = (System.nanoTime() - before) / 1_000_000;
        assertEquals(body(r), 400, r.getResponse().getStatus());
        assertTrue(body(r), body(r).contains("Retry limit"));
        assertTrue("the loop must end quickly, took " + ms + " ms", ms < 10_000);
        assertEquals("counted as one harmful loop", 1, harmfulLoops(p));

        // исправление счётчиков убирает причину: пул честно пуст, это уже не вредный цикл
        mvc.perform(post("/api/v1/pools/repair?env=tst&pool=" + p)).andReturn();
        MvcResult after = next(p, false);
        assertEquals(400, after.getResponse().getStatus());
        assertFalse(body(after), body(after).contains("Retry limit"));
        assertEquals(1, harmfulLoops(p));
    }

    @Test
    public void thirtyPercentLockedInTheDbOnlyIsStillFiniteAndSkipsLockedRows() throws Exception {
        String p = poolName(6);
        fill(p, ROWS);
        Random rnd = new Random(5);
        Set<Integer> dbLocked = new HashSet<>();
        for (int rid = 1; rid <= ROWS; rid++) if (rnd.nextInt(100) < 30) { dbLocked.add(rid); jdbc.update("update tst." + p + " set locked = true where rid = ?", rid); }
        for (int i = 0; i < 600; i++) {
            MvcResult r = next(p, false);
            assertEquals(body(r), 200, r.getResponse().getStatus());
            assertFalse(dbLocked.contains(rid(body(r))));
        }
        assertEquals(0, harmfulLoops(p));
    }
}
