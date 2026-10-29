package load.datapool.service;

import load.datapool.prometheus.Exporter;
import load.datapool.repository.PoolRepository;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** PoolService в изоляции: репозиторий, кэш блокировок и sequence подменены. */
public class PoolServiceTest {

    private PoolRepository repository;
    private LockerService lockers;
    private SequenceService sequences;
    private Exporter exp;
    private Locker locker;
    private PoolService service;

    @Before
    public void setUp() {
        repository = mock(PoolRepository.class);
        lockers = mock(LockerService.class);
        sequences = mock(SequenceService.class);
        exp = mock(Exporter.class);
        locker = new BaseLocker("TST.P", 5);
        when(lockers.poolExist("tst", "p")).thenReturn(true);
        when(lockers.getLocker("tst", "p")).thenReturn(locker);
        service = new PoolService(repository, lockers, sequences, exp);
        ReflectionTestUtils.setField(service, "getNextMaxRetries", 3);
    }

    @Test
    public void nextValueReturnsRowOfTheCursor() {
        when(sequences.nextRid("tst", "p")).thenReturn(2L);
        when(repository.selectRowJson("tst", "p", 2, false)).thenReturn("{\"rid\":2}");
        assertEquals("{\"rid\":2}", service.nextValue("tst", "p", false));
        verify(lockers, never()).tryLock(anyString(), anyString(), anyInt());
    }

    @Test
    public void lockedReadTakesTheRowAtomically() {
        when(sequences.nextRid("tst", "p")).thenReturn(1L);
        when(lockers.tryLock("tst", "p", 1)).thenReturn(true);
        when(repository.selectRowJson("tst", "p", 1, true)).thenReturn("{\"rid\":1}");
        assertEquals("{\"rid\":1}", service.nextValue("tst", "p", true));
        verify(lockers).tryLock("tst", "p", 1);
    }

    @Test
    public void missingRowMovesCursorToNextFreeRowAndRetries() {
        when(sequences.nextRid("tst", "p")).thenReturn(2L, 3L);
        when(repository.selectRowJson("tst", "p", 2, false)).thenReturn(null);          // дыра
        when(repository.selectRowJson("tst", "p", 3, false)).thenReturn("{\"rid\":3}");
        when(lockers.firstBiggerUnlockedId("tst", "p", 2)).thenReturn(3);
        assertEquals("{\"rid\":3}", service.nextValue("tst", "p", false));
        verify(sequences).restartRid("tst", "p", 3);
        assertEquals("cursor epoch grows after the move", 1, locker.cursorEpoch());
    }

    @Test
    public void retryLimitEndsTheLoop() {
        when(sequences.nextRid("tst", "p")).thenReturn(2L);
        when(repository.selectRowJson(anyString(), anyString(), anyLong(), anyBoolean())).thenReturn(null);
        when(lockers.firstBiggerUnlockedId(anyString(), anyString(), anyInt())).thenReturn(3);
        try {
            service.nextValue("tst", "p", false);
            fail();
        } catch (PoolException e) {
            assertEquals("Retry limit exceeded", e.getDetail());
            assertTrue(e.getMessage().contains("Retry limit 3"));
        }
        verify(sequences, times(3)).nextRid("tst", "p");
    }

    @Test
    public void nothingFreeMarksPoolEmpty() {
        when(sequences.nextRid("tst", "p")).thenReturn(5L);
        when(repository.selectRowJson(anyString(), anyString(), anyLong(), anyBoolean())).thenReturn(null);
        when(lockers.firstBiggerUnlockedId("tst", "p", 5)).thenReturn(0);
        when(lockers.firstUnlockRid("tst", "p")).thenReturn(0);
        try {
            service.nextValue("tst", "p", false);
            fail();
        } catch (PoolException e) {
            assertEquals("Sequence does not reset", e.getDetail());
        }
        verify(lockers).markAsEmpty("tst", "p");
        verify(sequences).restartRid("tst", "p", 1);
    }

    @Test
    public void emptyPoolAnswersWithoutTouchingTheDb() {
        when(lockers.isMarkedAsEmpty("tst", "p")).thenReturn(true);
        try {
            service.nextValue("tst", "p", false);
            fail();
        } catch (PoolException e) {
            assertEquals("Empty pool", e.getDetail());
        }
        verifyZeroInteractions(sequences, repository);
    }

    @Test
    public void unknownPoolIsReported() {
        try {
            service.nextValue("tst", "other", false);
            fail();
        } catch (PoolException e) {
            assertEquals("Pool not found", e.getDetail());
            assertEquals("Table tst.other not found in lockerService", e.getMessage());
        }
    }

    @Test
    public void badNamesNeverReachTheRepository() {
        String[] calls = {"n", "p", "u", "s", "c", "d", "r"};
        for (String c : calls) {
            try {
                switch (c) {
                    case "n": service.nextValue("tst", "p;drop", false); break;
                    case "p": service.put("t s", "p", "", "{}"); break;
                    case "u": service.unlock("tst", "a.b", "1", "", false); break;
                    case "s": service.searchByKey("tst", "a'b", "k", false); break;
                    case "c": service.uploadCsv("tst", "a b", ",", false, true, 5, new ByteArrayInputStream("a".getBytes())); break;
                    case "d": service.drop("tst", "x--"); break;
                    default: service.resetSequence("tst", "x;y");
                }
                fail(c);
            } catch (PoolException e) {
                assertTrue(c, e.isBadName());
            }
        }
        verifyZeroInteractions(repository, sequences);
        verify(lockers, never()).poolExist(anyString(), anyString());
    }

    @Test
    public void putGrowsCacheAfterInsertAndKeepsCacheOnFailure() {
        when(sequences.nextMax("tst", "p")).thenReturn(6L);
        String r = service.put("tst", "p", "k", "{}");
        assertTrue(r.contains("searchKey = k"));
        verify(repository).insert("tst", "p", 6, "{}", "k");
        verify(lockers).ensureSize("tst", "p", 6);

        doThrow(new RuntimeException("too long")).when(repository).insert("tst", "p", 7, "x", "");
        when(sequences.nextMax("tst", "p")).thenReturn(7L);
        try {
            service.put("tst", "p", "", "x");
            fail();
        } catch (PoolException e) {
            assertEquals("Undefined INSERT exception", e.getDetail());
        }
        verify(lockers, never()).ensureSize("tst", "p", 7);
    }

    @Test
    public void putRejectsLongNameOfNewPool() {
        when(lockers.poolExist("tst", "abcdefghijklmnopq")).thenReturn(false);
        try {
            service.put("tst", "abcdefghijklmnopq", "", "{}");
            fail();
        } catch (PoolException e) {
            assertTrue(e.getMessage().contains("16"));
        }
        verify(repository, never()).createPool(anyString(), anyString());
    }

    @Test
    public void unlockNeedsOneOfTheParameters() {
        try {
            service.unlock("tst", "p", "-1", "", false);
            fail();
        } catch (PoolException e) {
            assertEquals("Wrong request parameters", e.getDetail());
        }
        assertEquals("Unlocked successfully!", service.unlock("tst", "p", "3", "", false));
        verify(lockers).unlock("tst", "p", 3);
        service.unlock("tst", "p", "-1", "key", false);
        verify(lockers).unlock("tst", "p", "key");
        service.unlock("tst", "p", "-1", "", true);
        verify(lockers).unlockAll("tst", "p");
    }

    @Test
    public void unlockRejectsNonNumericRid() {
        try {
            service.unlock("tst", "p", "abc", "", false);
            fail();
        } catch (PoolException e) {
            assertEquals("NumberFormatException", e.getDetail());
        }
    }

    @Test
    public void searchLockedRowTakenByAnotherRequestIsRefused() {
        when(repository.findFreeByKey(eq("tst"), eq("p"), eq("k"), any())).thenReturn("{}");
        when(lockers.tryLock(anyString(), anyString(), anyInt())).thenReturn(false);
        // rid[0] остаётся null без вызова mapper: имитируем вызов mapper через thenAnswer
        when(repository.findFreeByKey(eq("tst"), eq("p"), eq("k"), any())).thenAnswer(inv -> {
            PoolRepository.RowMapper<String> m = inv.getArgument(3);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getLong("rid")).thenReturn(9L);
            when(rs.getString("searchkey")).thenReturn("k");
            when(rs.getString("text")).thenReturn("{}");
            return m.map(rs);
        });
        try {
            service.searchByKey("tst", "p", "k", true);
            fail();
        } catch (PoolException e) {
            assertEquals("Already locked", e.getDetail());
        }
    }

    @Test
    public void uploadValidatesBeforeTouchingData() {
        try {
            service.uploadCsv("tst", "p", ",", true, false, 5, new ByteArrayInputStream(new byte[0]));
            fail();
        } catch (PoolException e) { assertEquals("With out headers csv", e.getDetail()); }
        try {
            service.uploadCsv("tst", "p", ",", true, true, 0, new ByteArrayInputStream(new byte[0]));
            fail();
        } catch (PoolException e) { assertEquals("File is empty", e.getDetail()); }
        verify(repository, never()).dropPool(anyString(), anyString());
    }

    @Test
    public void dropReportsMissingPoolAndRemovesExisting() {
        when(lockers.poolExist("tst", "gone")).thenReturn(false);
        try {
            service.drop("tst", "gone");
            fail();
        } catch (PoolException e) { assertEquals("Pool not found", e.getDetail()); }
        assertEquals("Pool deleted successfully!", service.drop("tst", "p"));
        verify(lockers).deletePool("tst", "p");
        verify(repository).dropPool("tst", "p");
        verify(exp).removeMetrics("tst", "p");
    }

    @Test
    public void dropFailureIsReported() {
        doThrow(new RuntimeException("x")).when(repository).dropPool("tst", "p");
        try {
            service.drop("tst", "p");
            fail();
        } catch (PoolException e) { assertEquals("Failed to delete pool", e.getDetail()); }
    }
}
