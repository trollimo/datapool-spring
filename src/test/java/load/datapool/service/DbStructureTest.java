package load.datapool.service;

import load.datapool.AbstractSpringTest;
import org.junit.Test;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

/**
 * Структура БД пула не должна меняться: тест сверяет DDL нового пула с эталоном, снятым с БД версии 2.0.0
 * (она совпадает со структурой 1.7.x). Менять структуру существующих БД без миграции нельзя.
 */
public class DbStructureTest extends AbstractSpringTest {

    private static final List<String> GOLDEN = Arrays.asList(
            "CREATE CACHED TABLE TST.<POOL>( RID BIGINT NOT NULL, TEXT VARCHAR(32000), SEARCHKEY VARCHAR(1500), LOCKED BOOLEAN DEFAULT FALSE NOT NULL );",
            "ALTER TABLE TST.<POOL> ADD CONSTRAINT TST.<CONSTRAINT> PRIMARY KEY(RID);",
            "CREATE INDEX TST.IDX_TST<POOL>_RID ON TST.<POOL>(RID);",
            "CREATE INDEX TST.IDX_TST<POOL>_LC ON TST.<POOL>(LOCKED);",
            "CREATE INDEX TST.IDX_TST<POOL>_SK ON TST.<POOL>(SEARCHKEY, LOCKED);",
            "CREATE SEQUENCE TST.<SEQ>_MAX MINVALUE 0 CACHE 5;",
            "CREATE SEQUENCE TST.<SEQ>_RID MINVALUE 0 CACHE 5;");

    private String normalize(String sql, String poolUpper) {
        String seq = ("SEQ_" + poolUpper);
        seq = seq.substring(0, Math.min(seq.length(), 25 - "TST.".length()));
        String s = sql.replaceAll("\\s+", " ").trim().replace("CREATE MEMORY TABLE", "CREATE CACHED TABLE");
        s = s.replaceAll("SELECTIVITY \\d+", "").replaceAll("START WITH \\d+", "").replaceAll("\\s+", " ").replace("( ", "( ").replace(" )", " )");
        s = s.replaceAll("CONSTRAINT_[0-9A-F_]+", "<CONSTRAINT>").replaceAll("PRIMARY_KEY_[0-9A-F_]+", "<CONSTRAINT>");
        s = s.replace("TST." + seq, "TST.<SEQ>").replace(poolUpper, "<POOL>");
        return s.replaceAll(" +;", ";").replace("MINVALUE 0 CACHE 5", "MINVALUE 0 CACHE 5").replaceAll("\\( ", "( ").replaceAll(" +", " ");
    }

    @Test
    public void newPoolHasTheSameStructureAsBefore() throws Exception {
        pool = "dbs" + freshName().substring(1, 6);
        fill(1);
        String up = pool.toUpperCase();
        List<String> mine = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SCRIPT NODATA NOPASSWORDS NOSETTINGS")) {
            String line = row.values().iterator().next().toString();
            if (line.contains("TST." + up) || line.contains("SEQ_" + up.substring(0, Math.min(up.length(), 21)))) {
                if (line.startsWith("--")) continue;
                mine.add(normalize(line, up));
            }
        }
        List<String> expected = new ArrayList<>();
        for (String g : GOLDEN) expected.add(g.replaceAll(" +", " "));
        Collections.sort(mine);
        Collections.sort(expected);
        assertEquals(expected.toString(), mine.toString());
    }
}
