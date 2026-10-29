package load.datapool.todo.rest;

import load.datapool.AbstractSpringTest;
import org.junit.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class PoolsIntegrationTest extends AbstractSpringTest {

    @Test
    public void holeInRidDoesNotLoopForever() throws Exception {
        fill(5);
        jdbc.update("delete from tst." + pool + " where rid = 3");
        for (int i = 0; i < 20; i++) {
            MvcResult r = next(false);
            assertEquals(r.getResponse().getContentAsString(), 200, r.getResponse().getStatus());
            assertNotEquals(3, rid(r.getResponse().getContentAsString()));
        }
    }

    @Test
    public void failedInsertDoesNotBreakPool() throws Exception {
        fill(3);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 33000; i++) big.append('x');
        MvcResult bad = mvc.perform(post("/api/v1/put-value?env=tst&pool=" + pool).content(big.toString())).andReturn();
        assertEquals(400, bad.getResponse().getStatus());
        fill(2);
        Set<Integer> got = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            MvcResult r = next(true);
            assertEquals(r.getResponse().getContentAsString(), 200, r.getResponse().getStatus());
            got.add(rid(r.getResponse().getContentAsString()));
        }
        assertEquals(5, got.size());
    }

    @Test
    public void parallelLockedReadsNeverReturnDuplicates() throws Exception {
        final int rows = 300;
        fill(rows);
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            fs.add(ex.submit(() -> {
                MvcResult r = next(true);
                return r.getResponse().getStatus() == 200 ? rid(r.getResponse().getContentAsString()) : -1;
            }));
        }
        Set<Integer> seen = new HashSet<>();
        for (Future<Integer> f : fs) {
            int rid = f.get(60, TimeUnit.SECONDS);
            assertTrue("request failed", rid > 0);
            assertTrue("duplicate rid " + rid, seen.add(rid));
        }
        ex.shutdown();
        assertEquals(rows, seen.size());
        // пул исчерпан: ответ — «пуст», а не зависание и не 500
        MvcResult r = next(true);
        assertEquals(400, r.getResponse().getStatus());
    }

    private com.fasterxml.jackson.databind.JsonNode myPool() throws Exception {
        String list = mvc.perform(get("/api/v1/pools")).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode all = new com.fasterxml.jackson.databind.ObjectMapper().readTree(list);
        boolean seenHealthy = false;
        com.fasterxml.jackson.databind.JsonNode mine = null;
        for (com.fasterxml.jackson.databind.JsonNode p : all) {
            if (!p.get("diverged").asBoolean()) seenHealthy = true;
            else assertFalse("diverged pools must come first: " + p, seenHealthy);
            if (p.get("pool").asText().equals("TST." + pool.toUpperCase())) mine = p;
        }
        assertNotNull(mine);
        return mine;
    }

    @Test
    public void poolsListShowsDivergedFirstAndRepairFixesIt() throws Exception {
        fill(4);
        jdbc.update("update tst." + pool + " set locked = true where rid = 2"); // БД знает о блокировке, кэш — нет
        com.fasterxml.jackson.databind.JsonNode before = myPool();
        assertTrue(before.get("diverged").asBoolean());
        assertEquals(1, before.get("lockedOnlyInDb").asInt());

        mvc.perform(post("/api/v1/pools/repair-batch").contentType("application/json")
                .content("[\"TST." + pool.toUpperCase() + "\"]")).andReturn();

        assertFalse(myPool().get("diverged").asBoolean());
        // после исправления занятая строка 2 больше не выдаётся
        Set<Integer> got = new HashSet<>();
        for (int i = 0; i < 3; i++) got.add(rid(next(true).getResponse().getContentAsString()));
        assertEquals(new HashSet<>(Arrays.asList(1, 3, 4)), got);
    }

    @Test
    public void repairUsesSameSequenceAsResetseq() throws Exception {
        fill(3);
        next(false);
        next(false);
        mvc.perform(post("/api/v1/pools/repair?env=tst&pool=" + pool)).andReturn();
        assertEquals(1, rid(next(false).getResponse().getContentAsString()));
    }
}
