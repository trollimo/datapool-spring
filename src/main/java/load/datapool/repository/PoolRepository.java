package load.datapool.repository;

import load.datapool.db.FullRow;
import load.datapool.db.H2Template;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Весь SQL пулов. Имена env и pool сюда приходят уже проверенными (PoolNames), потому что они подставляются в текст запроса.
 * Структура таблиц, индексов и sequence в БД не менялась с версии 1.7.x: тест DbStructureTest сверяет её с эталоном.
 */
@Repository
public class PoolRepository {

    private static final int MAX_SEQUENCE_LENGTH = 25;
    private static final int MAX_INDEX_LENGTH = 25;
    private static final int SEQ_CYCLE_CACHE = 5;

    /** Одна строка пула для get-next-value / search-by-key. */
    public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private final H2Template jdbc;
    @Value("${db.maxKeyColumnLength:1500}")
    private int maxKeyColumnLength;
    @Value("${db.maxTextColumnLength:32000}")
    private int maxTextColumnLength;
    @Value("${db.viewer.username:viewer}")
    private String viewerName;
    @Value("${db.viewer.password:viewer}")
    private String viewerPassword;

    @Autowired
    public PoolRepository(H2Template jdbc) {
        this.jdbc = jdbc;
    }

    // ---- структура: создание и удаление пула ----

    public void createPool(String env, String pool) {
        jdbc.execute("create schema if not exists " + env + ";");
        jdbc.execute("" +
                "CREATE USER IF NOT EXISTS " + viewerName + " PASSWORD '" + viewerPassword + "';\n" +
                "GRANT SELECT ON SCHEMA  " + env + " TO " + viewerName + ";");
        jdbc.execute("" +
                "create table " + env + "." + pool +
                "(" +
                "        rid           bigint primary key," +
                "        text        varchar(" + maxTextColumnLength + ")," +
                "        searchkey        varchar(" + maxKeyColumnLength + ")," +
                "        locked         boolean   not null default false" +
                ");");
        String indexPrefix = indexPrefix(env, pool);
        jdbc.execute("CREATE INDEX " + indexPrefix + "_rid ON  " + env + "." + pool + " (rid);");
        jdbc.execute("CREATE INDEX " + indexPrefix + "_lc ON  " + env + "." + pool + " (locked);");
        jdbc.execute("CREATE INDEX " + indexPrefix + "_sk ON  " + env + "." + pool + " (searchkey, locked);");

        jdbc.execute("DROP SEQUENCE if exists " + maxSequence(env, pool) + ";");
        jdbc.execute("DROP SEQUENCE if exists " + ridSequence(env, pool) + ";");
        jdbc.execute("CREATE SEQUENCE " + maxSequence(env, pool) + " MINVALUE 0 NO CYCLE CACHE " + SEQ_CYCLE_CACHE + " INCREMENT 1 START 1;"); //for append insert
        jdbc.execute("CREATE SEQUENCE " + ridSequence(env, pool) + " MINVALUE 0 NO CYCLE CACHE " + SEQ_CYCLE_CACHE + " INCREMENT 1 START 1;"); //for get value
    }

    public void dropPool(String env, String pool) {
        jdbc.execute("drop table if exists " + env + "." + pool + ";");
        String indexPrefix = indexPrefix(env, pool);
        jdbc.execute("drop INDEX if exists " + indexPrefix + "_rid");
        jdbc.execute("drop INDEX if exists " + indexPrefix + "_lc");
        jdbc.execute("DROP SEQUENCE if exists " + maxSequence(env, pool) + ";");
        jdbc.execute("DROP SEQUENCE if exists " + ridSequence(env, pool) + ";");
    }

    private String indexPrefix(String env, String pool) {
        String prefix = "idx_" + env + "" + pool;
        return prefix.substring(0, Math.min(prefix.length(), MAX_INDEX_LENGTH));
    }

    // ---- имена sequence ----

    public String seqPrefix(String env, String pool) {
        String prefix = env + "." + "seq_" + pool;
        return prefix.substring(0, Math.min(prefix.length(), MAX_SEQUENCE_LENGTH));
    }

    public String ridSequence(String env, String pool) { return seqPrefix(env, pool) + "_rid"; }

    public String maxSequence(String env, String pool) { return seqPrefix(env, pool) + "_max"; }

    public long nextval(String sequence) {
        return jdbc.queryForObject("select nextval (?)", new String[]{sequence}, Long.class);
    }

    public void restartSequence(String sequence, long value) {
        jdbc.update("ALTER SEQUENCE " + sequence + " RESTART WITH ?", value);
    }

    /** Последнее выданное значение sequence или null. */
    public Long currentSequenceValue(String schema, String sequenceName) {
        try {
            return jdbc.queryForObject("SELECT current_value FROM information_schema.sequences WHERE sequence_schema = ? AND sequence_name = ?",
                    Long.class, schema, sequenceName);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- строки ----

    public void insert(String env, String pool, long rid, String text, String searchKey) {
        if (searchKey == null || searchKey.equals("")) {
            jdbc.update("insert into " + env + "." + pool + "(rid, text, locked) values (?,?,?);", rid, text, false);
        } else {
            jdbc.update("insert into " + env + "." + pool + "(rid, text, searchkey, locked) values (?,?,?,?);", rid, text, searchKey, false);
        }
    }

    /** Вставка строки при загрузке CSV: rid берётся из sequence _max прямо в запросе. */
    public void insertCsvRow(String env, String pool, String json) {
        jdbc.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", maxSequence(env, pool), json, false);
    }

    /** JSON строки для get-next-value; null, если строки нет (дыра в rid) или она занята и locked=false. */
    public String selectRowJson(String env, String pool, long rid, boolean locked) {
        List<String> rows = jdbc.query("select rid, text from " + env + "." + pool + " where rid = ? and (locked = false or ?) limit 1",
                (rs, i) -> "{\"rid\":" + rs.getLong("rid") +
                        ",\"values\":" + rs.getString("text") +
                        ",\"locked\":" + locked + "}", rid, locked);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Первая свободная строка с ключом; EmptyResultDataAccessException, если такой нет. */
    public <T> T findFreeByKey(String env, String pool, String searchKey, RowMapper<T> mapper) {
        return jdbc.queryForObject(
                "select rid, text,searchkey, locked from " + env + "." + pool + " where searchkey = ? and locked = false limit 1",
                (rs, i) -> mapper.map(rs), searchKey);
    }

    // ---- блокировки ----

    /** Атомарно: true только если строка была свободна. */
    public boolean tryLock(String fullPool, int rid) {
        return jdbc.update("update " + fullPool + " set locked = true where rid = ? and locked = false", rid) == 1;
    }

    public void lock(String fullPool, int rid) {
        jdbc.update("update " + fullPool + " set locked = true where  rid = ? and locked = false;", rid);
    }

    public void unlock(String fullPool, int rid) {
        jdbc.update("update " + fullPool + " set locked = false where rid = ?", rid);
    }

    /** Освобождает строки с ключом и возвращает rid первой из них (EmptyResultDataAccessException, если ключа нет). */
    public Integer unlockByKey(String fullPool, String searchKey) {
        jdbc.update("update " + fullPool + " set locked = false where searchkey = ?", searchKey);
        return jdbc.queryForObject("SELECT rid FROM " + fullPool + " WHERE searchkey = ? limit 1", Integer.class, searchKey);
    }

    public void unlockAll(String fullPool) {
        jdbc.update("update " + fullPool + " set locked = false where locked is null; update " + fullPool + " set locked = false where locked =true;"); //Fast variant
    }

    // ---- счётчики и содержимое для кэша и диагностики ----

    public Integer maxRid(String fullPool) {
        return jdbc.queryForObject("SELECT max(rid) FROM " + fullPool, Integer.class);
    }

    public long countRows(String fullPool) {
        Long n = jdbc.queryForObject("SELECT count(rid) FROM " + fullPool, Long.class);
        return n == null ? 0 : n;
    }

    public Integer lockedRows(String fullPool) {
        return jdbc.queryForObject("SELECT count(rid) FROM " + fullPool + " WHERE locked = true", Integer.class);
    }

    public List<Integer> lockedRids(String fullPool) {
        return jdbc.queryForList("SELECT rid FROM " + fullPool + " WHERE locked = true", Integer.class);
    }

    /** Занятые rid порциями: rid > after, не больше limit штук. */
    public List<Integer> lockedRidsAfter(String fullPool, int after, int limit) {
        return jdbc.queryForList("SELECT rid FROM " + fullPool + " WHERE rid > ? AND locked = true limit ?", new Object[]{after, limit}, Integer.class);
    }

    /** Порция строк для выгрузки. Без LIMIT/OFFSET: H2 к концу таблицы тормозит, поэтому WHERE RID >= offset. */
    public List<FullRow> exportBatch(String env, String pool, boolean allColumns, int offsetRid, int limit) {
        String sql = allColumns
                ? "SELECT RID as rid, TEXT as text, SEARCHKEY as searchkey, LOCKED as locked FROM " + env + "." + pool
                : "SELECT TEXT FROM " + env + "." + pool;
        return jdbc.query(sql + " WHERE RID >= " + offsetRid + " LIMIT " + limit, (rs, rowNum) -> {
            FullRow fullRow = new FullRow();
            fullRow.setText(rs.getString("text"));
            if (allColumns) {
                fullRow.setRid(rs.getInt("rid"));
                fullRow.setSearchkey(rs.getString("searchkey"));
                fullRow.setLocked(rs.getBoolean("locked"));
            }
            return fullRow;
        });
    }

    // ---- каталог БД (поиск пулов при старте) ----

    public List<String> schemas() {
        List<String> res = new java.util.ArrayList<>();
        jdbc.queryForList("SHOW SCHEMAS").forEach(map -> map.forEach((key, value) -> res.add(value.toString())));
        return res;
    }

    public List<String> tables(String schema) {
        return jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.tables where table_schema = ?", new String[]{schema}, String.class);
    }

    public boolean hasColumn(String schema, String table, String column) {
        Integer n = jdbc.queryForObject("SELECT count(column_name) FROM INFORMATION_SCHEMA.COLUMNS WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                new String[]{schema, table, column}, Integer.class);
        return n != null && n > 0;
    }
}
