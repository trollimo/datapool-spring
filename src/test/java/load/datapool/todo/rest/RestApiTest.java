package load.datapool.todo.rest;

import load.datapool.AbstractSpringTest;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Функциональность REST API: загрузка, чтение по кругу, блокировки, поиск, выгрузка, удаление, сброс курсора. */
public class RestApiTest extends AbstractSpringTest {

    private MvcResult upload(String p, String csv, String delim, boolean override) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "x.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(multipart("/api/v1/upload-csv-as-json?env=tst&pool=" + p + "&delimiter=" + delim + "&override=" + override).file(f)).andReturn();
    }

    private MvcResult download(String p, String delim, boolean all) throws Exception {
        return mvc.perform(get("/api/v1/download/csv?env=tst&pool=" + p + "&delimiter=" + delim + "&all_columns=" + all)).andReturn();
    }

    @Test
    public void putValueCreatesPoolAndGetNextReadsInRing() throws Exception {
        fill(3);
        List<Integer> rids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            MvcResult r = next(false);
            assertEquals(200, r.getResponse().getStatus());
            rids.add(rid(body(r)));
        }
        assertEquals(Arrays.asList(1, 2, 3, 1, 2, 3, 1), rids);
    }

    @Test
    public void getNextReturnsRowAsJson() throws Exception {
        fill(1);
        assertEquals("{\"rid\":1,\"values\":{\"a\":\"0\"},\"locked\":false}", body(next(false)));
    }

    @Test
    public void unknownPoolIsBadRequest() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/get-next-value?env=tst&pool=nosuchpool")).andReturn();
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(body(r).contains("not found"));
    }

    @Test
    public void poolNameLongerThan16IsRejected() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/put-value?env=tst&pool=abcdefghijklmnopq").content("{}")).andReturn();
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(body(r).contains("16"));
    }

    @Test
    public void lockedRowIsNotIssuedAgainUntilUnlocked() throws Exception {
        fill(2);
        assertEquals(1, rid(body(next(true))));
        assertEquals(2, rid(body(next(true))));
        assertEquals("locked rows must not be issued", 400, next(true).getResponse().getStatus());

        MvcResult u = mvc.perform(post("/api/v1/unlock?env=tst&pool=" + pool + "&rid=2")).andReturn();
        assertEquals(200, u.getResponse().getStatus());
        assertEquals(2, rid(body(next(true))));
    }

    @Test
    public void unlockAllReleasesEveryRow() throws Exception {
        fill(3);
        for (int i = 0; i < 3; i++) next(true);
        assertEquals(400, next(true).getResponse().getStatus());
        mvc.perform(post("/api/v1/unlock?env=tst&pool=" + pool + "&unlock-all=true")).andReturn();
        Set<Integer> got = new HashSet<>();
        for (int i = 0; i < 3; i++) got.add(rid(body(next(true))));
        assertEquals(3, got.size());
    }

    @Test
    public void unlockWithoutParametersIsBadRequest() throws Exception {
        fill(1);
        assertEquals(400, mvc.perform(post("/api/v1/unlock?env=tst&pool=" + pool)).andReturn().getResponse().getStatus());
    }

    @Test
    public void searchByKeyFindsAndLocks() throws Exception {
        mvc.perform(post("/api/v1/put-value?env=tst&pool=" + pool + "&search-key=k1").content("{\"v\":\"one\"}")).andReturn();
        mvc.perform(post("/api/v1/put-value?env=tst&pool=" + pool + "&search-key=k2").content("{\"v\":\"two\"}")).andReturn();

        MvcResult r = mvc.perform(get("/api/v1/search-by-key?env=tst&pool=" + pool + "&search-key=k2&locked=true")).andReturn();
        assertEquals(body(r), 200, r.getResponse().getStatus());
        assertTrue(body(r).contains("\"searchkey\":\"k2\""));
        assertTrue(body(r).contains("\"two\""));
        // занятую строку по ключу больше не найти
        assertEquals(400, mvc.perform(get("/api/v1/search-by-key?env=tst&pool=" + pool + "&search-key=k2")).andReturn().getResponse().getStatus());
        // разблокировка по ключу
        mvc.perform(post("/api/v1/unlock?env=tst&pool=" + pool + "&search-key=k2")).andReturn();
        assertEquals(200, mvc.perform(get("/api/v1/search-by-key?env=tst&pool=" + pool + "&search-key=k2")).andReturn().getResponse().getStatus());
    }

    @Test
    public void searchWithoutKeyIsBadRequest() throws Exception {
        fill(1);
        assertEquals(400, mvc.perform(get("/api/v1/search-by-key?env=tst&pool=" + pool)).andReturn().getResponse().getStatus());
    }

    @Test
    public void uploadCsvCreatesRowsAsJson() throws Exception {
        MvcResult up = upload(pool, "a,b\n1,2\n3,4\n", ",", false);
        assertEquals(body(up), 200, up.getResponse().getStatus());
        assertTrue(body(up).contains("Uploaded rows: 2"));
        assertEquals("{\"rid\":1,\"values\":{\"a\":\"1\",\"b\":\"2\"},\"locked\":false}", body(next(false)));
    }

    @Test
    public void uploadWithOverrideRecreatesThePool() throws Exception {
        upload(pool, "a\n1\n2\n3\n", ",", false);
        upload(pool, "a\nx\ny\n", ",", true);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) seen.add(body(next(false)));
        assertEquals(2, seen.size());
        assertTrue(seen.iterator().next().contains("\"x\"") || seen.iterator().next().contains("\"y\""));
    }

    @Test
    public void uploadAppendsWhenOverrideIsFalse() throws Exception {
        upload(pool, "a\n1\n2\n", ",", false);
        upload(pool, "a\n3\n", ",", false);
        Set<Integer> rids = new HashSet<>();
        for (int i = 0; i < 6; i++) rids.add(rid(body(next(false))));
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), rids);
    }

    @Test
    public void uploadRejectsEmptyFileAndFileWithoutHeadersFlag() throws Exception {
        assertEquals(400, upload(pool, "", ",", false).getResponse().getStatus());
        MockMultipartFile f = new MockMultipartFile("file", "x.csv", "text/csv", "a\n1\n".getBytes());
        assertEquals(400, mvc.perform(multipart("/api/v1/upload-csv-as-json?env=tst&pool=" + pool + "&with-headers=false").file(f)).andReturn().getResponse().getStatus());
    }

    @Test
    public void downloadCsvHasHeadersAndRows() throws Exception {
        upload(pool, "a;b\n1;2\n3;4\n", ";", false);
        MvcResult r = download(pool, ";", false);
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentType().startsWith("text/csv"));
        assertEquals("a;b\n1;2\n3;4\n", body(r).replace("\r\n", "\n"));
        assertTrue(r.getResponse().getHeader("Content-Disposition").contains(".csv"));
    }

    @Test
    public void downloadWithAllColumnsAddsTechnicalFields() throws Exception {
        upload(pool, "a\n1\n", ",", false);
        String csv = body(download(pool, ",", true)).replace("\r\n", "\n");
        assertEquals("rid,searchkey,locked,a\n1,null,false,1\n", csv);
    }

    @Test
    public void downloadThenUploadRoundTripKeepsData() throws Exception {
        upload(pool, "name,city\nAnna,Oslo\nBob,Rome\nCid,Kyiv\n", ",", false);
        String csv = body(download(pool, ";", false));
        String other = freshName();
        MvcResult up = upload(other, csv, ";", false);
        assertEquals(body(up), 200, up.getResponse().getStatus());
        assertTrue(body(up).contains("Uploaded rows: 3"));
        assertEquals(body(download(pool, ",", false)), body(download(other, ",", false)));
    }

    @Test
    public void dropPoolRemovesEverything() throws Exception {
        fill(2);
        assertEquals(200, mvc.perform(delete("/api/v1/drop-pool?env=tst&pool=" + pool)).andReturn().getResponse().getStatus());
        assertEquals(400, next(false).getResponse().getStatus());
        assertEquals("second drop: pool is already gone", 400, mvc.perform(delete("/api/v1/drop-pool?env=tst&pool=" + pool)).andReturn().getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='TST' and table_name=?", Integer.class, pool.toUpperCase()).intValue());
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.sequences where sequence_name like ?", Integer.class, "SEQ_" + pool.toUpperCase() + "%").intValue());
        String list = body(mvc.perform(get("/api/v1/pools")).andReturn());
        assertFalse(list, list.contains("TST." + pool.toUpperCase() + "\""));
    }

    @Test
    public void dropAndRecreateStartsFromScratch() throws Exception {
        fill(3);
        mvc.perform(delete("/api/v1/drop-pool?env=tst&pool=" + pool)).andReturn();
        fill(1);
        assertEquals(1, rid(body(next(false))));
        assertEquals(1, rid(body(next(false))));
    }

    @Test
    public void resetseqStartsReadingFromTheBeginning() throws Exception {
        fill(4);
        next(false);
        next(false);
        assertEquals(200, mvc.perform(get("/api/v1/resetseq?env=tst&pool=" + pool)).andReturn().getResponse().getStatus());
        assertEquals(1, rid(body(next(false))));
    }

    @Test
    public void exhaustedPoolIsMarkedEmptyAndRecoversAfterUnlock() throws Exception {
        fill(1);
        next(true);
        assertEquals(400, next(true).getResponse().getStatus());
        assertEquals("marked empty: answers at once", 400, next(true).getResponse().getStatus());
        mvc.perform(post("/api/v1/unlock?env=tst&pool=" + pool + "&rid=1")).andReturn();
        assertEquals(200, next(true).getResponse().getStatus());
    }

    @Test
    public void putValueUpdatesSizeSoNewRowsAreRead() throws Exception {
        fill(2);
        next(false);
        next(false);
        fill(1);
        Set<Integer> got = new HashSet<>();
        for (int i = 0; i < 3; i++) got.add(rid(body(next(false))));
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), got);
    }

    @Test
    public void metricsEndpointAnswers() throws Exception {
        fill(1);
        next(false);
        MvcResult r = mvc.perform(get("/metrics")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(body(r).toUpperCase().contains(pool.toUpperCase()));
    }

    @Test
    public void rootAndPoolsPageAreServed() throws Exception {
        assertEquals(200, mvc.perform(get("/pools.html")).andReturn().getResponse().getStatus());
        String page = body(mvc.perform(get("/pools.html")).andReturn());
        assertTrue(page.contains("api/v1/download/csv"));
        assertTrue(page.contains("api/v1/drop-pool"));
        assertTrue(page.contains("repair-batch"));
    }

    @Test
    public void removedCycleEndpointsAreGone() throws Exception {
        assertEquals(404, mvc.perform(get("/api/v1/get-cycle-fix-sequence")).andReturn().getResponse().getStatus());
    }
}
