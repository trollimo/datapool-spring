package load.datapool;

import load.datapool.db.H2Template;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Общая база: один Spring-контекст на все тесты (метрики Prometheus регистрируются статически), H2 в памяти. */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;LOCK_MODE=3",
        "datapool.getNext.maxRetries=200"})
@AutoConfigureMockMvc
public abstract class AbstractSpringTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired protected MockMvc mvc;
    @Autowired protected H2Template jdbc;

    protected String pool;

    @Before
    public void newPool() {
        pool = "t" + (System.nanoTime() % 100000000L) + SEQ.incrementAndGet();
    }

    protected String freshName() {
        return "t" + (System.nanoTime() % 100000000L) + SEQ.incrementAndGet();
    }

    protected void fill(int n) throws Exception {
        fill(pool, n);
    }

    protected void fill(String p, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(post("/api/v1/put-value?env=tst&pool=" + p).content("{\"a\":\"" + i + "\"}")).andReturn();
        }
    }

    protected MvcResult next(boolean locked) throws Exception {
        return mvc.perform(get("/api/v1/get-next-value?env=tst&pool=" + pool + "&locked=" + locked)).andReturn();
    }

    protected static int rid(String body) {
        Matcher m = Pattern.compile("\"rid\":(\\d+)").matcher(body);
        assertTrue(body, m.find());
        return Integer.parseInt(m.group(1));
    }

    protected String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
