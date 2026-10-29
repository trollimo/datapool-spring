package load.datapool.todo.rest;

import load.datapool.db.H2Template;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;LOCK_MODE=3",
        "datapool.getNext.maxRetries=200"})
@AutoConfigureMockMvc
public class PoolsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired H2Template jdbc;

    private int seq = 0;
    private String pool;

    @Before
    public void newPool() {
        pool = "t" + System.nanoTime() % 100000000L + (seq++);
    }

    private void fill(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(post("/api/v1/put-value?env=tst&pool=" + pool).content("{\"a\":" + i + "}")).andReturn();
        }
    }

    private MvcResult next(boolean locked) throws Exception {
        return mvc.perform(get("/api/v1/get-next-value?env=tst&pool=" + pool + "&locked=" + locked)).andReturn();
    }

    private static int rid(String body) {
        Matcher m = Pattern.compile("\"rid\":(\\d+)").matcher(body);
        assertTrue(body, m.find());
        return Integer.parseInt(m.group(1));
    }

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

    @Test
    public void poolsListShowsDivergedFirstAndRepairFixesIt() throws Exception {
        fill(4);
        jdbc.update("update tst." + pool + " set locked = true where rid = 2"); // БД знает о блокировке, кэш — нет
        String list = mvc.perform(get("/api/v1/pools")).andReturn().getResponse().getContentAsString();
        assertTrue(list, list.startsWith("[{\"pool\":\"TST." + pool.toUpperCase() + "\",\"diverged\":true"));
        assertTrue(list, list.contains("\"lockedOnlyInDb\":1"));

        mvc.perform(post("/api/v1/pools/repair-batch").contentType("application/json")
                .content("[\"TST." + pool.toUpperCase() + "\"]")).andReturn();

        list = mvc.perform(get("/api/v1/pools")).andReturn().getResponse().getContentAsString();
        assertFalse(list, list.contains("\"diverged\":true"));
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
