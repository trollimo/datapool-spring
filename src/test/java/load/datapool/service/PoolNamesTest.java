package load.datapool.service;

import org.junit.Test;

import static org.junit.Assert.*;

public class PoolNamesTest {

    @Test
    public void acceptsLettersDigitsUnderscore() {
        assertTrue(PoolNames.valid("load"));
        assertTrue(PoolNames.valid("Demo_clients_2"));
        assertTrue(PoolNames.valid("p1"));
        assertTrue(PoolNames.valid("_x"));
    }

    @Test
    public void rejectsEverythingElse() {
        String[] bad = {null, "", " ", "a b", "a.b", "a;b", "a'b", "a\"b", "a-b", "a/*", "x--", "a\nb", "ä", "a,b", "a(b)", "load.p1"};
        for (String b : bad) assertFalse("[" + b + "]", PoolNames.valid(b));
    }

    @Test
    public void rejectsTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('a');
        assertTrue(PoolNames.valid(sb.toString()));
        assertFalse(PoolNames.valid(sb.append('a').toString()));
    }

    @Test
    public void requireThrowsBadNameWithoutEchoingHugeInput() {
        try {
            PoolNames.require("ok", "x; drop table y");
            fail();
        } catch (PoolException e) {
            assertTrue(e.isBadName());
            assertEquals(400, e.getStatus());
            assertTrue(e.getMessage().contains("pool"));
        }
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append('!');
        try {
            PoolNames.require(big.toString(), "p");
            fail();
        } catch (PoolException e) {
            assertTrue(e.getMessage().length() < 200);
        }
    }
}
