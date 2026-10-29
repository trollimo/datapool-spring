package load.datapool.service;

import load.datapool.AbstractSpringTest;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Сервисы: sequence, реестр локеров, диагностика, повторная загрузка кэша из БД (как при рестарте). */
public class ServicesTest extends AbstractSpringTest {

    @Autowired SequenceService sequences;
    @Autowired LockerService lockers;
    @Autowired PoolDiagnosticsService diagnostics;

    @Test
    public void sequenceNamesAreTruncatedLikeTheOldCode() {
        assertEquals("tst.seq_abc_rid", sequences.ridSequence("tst", "abc"));
        assertEquals("tst.seq_abc_max", sequences.maxSequence("tst", "abc"));
        String longName = sequences.seqPrefix("environment_long", "pool_long_name1");
        assertEquals(25, longName.length());
    }

    @Test
    public void restartRidSetsTheCursorAndNextRidFollows() throws Exception {
        fill(5);
        sequences.restartRid("tst", pool, 4);
        assertEquals(4, sequences.nextRid("tst", pool));
        assertEquals(5, sequences.nextRid("tst", pool));
        assertNotNull(sequences.currentRid("tst", pool));
    }

    @Test
    public void nextMaxGrowsWithInserts() throws Exception {
        fill(3);
        assertEquals(4, sequences.nextMax("tst", pool));
    }

    @Test
    public void currentRidOfMissingSequenceIsNull() {
        assertNull(sequences.currentRid("tst", "nosuchpool"));
    }

    @Test
    public void listPoolsPutsDivergedFirst() throws Exception {
        fill(2);
        String healthy = pool;
        pool = freshName();
        fill(2);
        jdbc.update("update tst." + pool + " set locked = true where rid = 1");
        List<Map<String, Object>> list = diagnostics.listPools();
        assertEquals(("TST." + pool).toUpperCase(), list.get(0).get("pool"));
        assertEquals(true, list.get(0).get("diverged"));
        boolean seenHealthy = false;
        for (Map<String, Object> m : list) if (m.get("pool").equals(("TST." + healthy).toUpperCase())) { seenHealthy = true; assertEquals(false, m.get("diverged")); }
        assertTrue(seenHealthy);
    }

    @Test
    public void sizeDifferenceIsDivergence() throws Exception {
        fill(2);
        jdbc.update("insert into tst." + pool + "(rid, text, locked) values (50, '{}', false)");   // строка, которой кэш не знает
        Map<String, Object> m = diagnostics.listPools().stream().filter(x -> x.get("pool").equals(("TST." + pool).toUpperCase())).findFirst().get();
        assertEquals(true, m.get("diverged"));
        assertEquals(50, m.get("dbMaxRid"));
        assertEquals(2, m.get("lockerSize"));
        assertEquals(47, m.get("holes"));
    }

    @Test
    public void repairMissingPoolReportsError() {
        Map<String, Object> r = diagnostics.repair("tst", "nosuchpool");
        assertEquals(false, r.get("ok"));
        assertEquals("Pool not found", r.get("message"));
    }

    @Test
    public void repairOfFullyLockedPoolMarksItEmptyAndKeepsCursorValid() throws Exception {
        fill(2);
        next(true);
        next(true);
        jdbc.update("update tst." + pool + " set locked = true");
        Map<String, Object> r = diagnostics.repair("tst", pool);
        assertEquals(true, r.get("ok"));
        assertEquals(1, r.get("cursor"));
        assertTrue(lockers.isMarkedAsEmpty("tst", pool));
    }

    @Test
    public void repairOfEmptyPoolWorks() throws Exception {
        fill(1);
        jdbc.update("delete from tst." + pool);
        Map<String, Object> r = diagnostics.repair("tst", pool);
        assertEquals(true, r.get("ok"));
        assertEquals(0, r.get("lockerSize"));
    }

    @Test
    public void repairAllReturnsResultPerPool() throws Exception {
        fill(1);
        String other = freshName();
        fill(other, 1);
        List<Map<String, Object>> res = diagnostics.repairAll(java.util.Arrays.asList(("TST." + pool).toUpperCase(), ("TST." + other).toUpperCase()));
        assertEquals(2, res.size());
        for (Map<String, Object> m : res) assertEquals(true, m.get("ok"));
    }

    @Test
    public void reloadFromDbRebuildsLockerLikeAfterRestart() throws Exception {
        fill(4);
        jdbc.update("update tst." + pool + " set locked = true where rid in (1, 3)");
        lockers.reloadFromDb("tst", pool);
        Locker l = (Locker) lockers.getLocker("tst", pool);
        assertEquals(4, l.getSize());
        assertEquals(java.util.Arrays.asList(1, 3), l.lockedIds());
        assertEquals(2, l.firstUnlockId());
    }

    @Test
    public void tryLockIsAtomic() throws Exception {
        fill(1);
        assertTrue(lockers.tryLock("tst", pool, 1));
        assertFalse(lockers.tryLock("tst", pool, 1));
        lockers.unlock("tst", pool, 1);
        assertTrue(lockers.tryLock("tst", pool, 1));
    }

    @Test
    public void allLockersSnapshotContainsCreatedPools() throws Exception {
        fill(1);
        assertTrue(lockers.allLockers().containsKey(("TST." + pool).toUpperCase()));
    }

    @Test
    public void repairEndpointsAnswerJson() throws Exception {
        fill(2);
        String one = mvc.perform(post("/api/v1/pools/repair?env=tst&pool=" + pool)).andReturn().getResponse().getContentAsString();
        assertTrue(one, one.contains("\"ok\":true"));
        String batch = mvc.perform(post("/api/v1/pools/repair-batch").contentType("application/json").content("[\"TST." + pool.toUpperCase() + "\",\"TST.NOSUCH\"]")).andReturn().getResponse().getContentAsString();
        assertTrue(batch, batch.contains("\"ok\":true") && batch.contains("Pool not found"));
    }
}
