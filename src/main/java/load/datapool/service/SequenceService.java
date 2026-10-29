package load.datapool.service;

import load.datapool.db.H2Template;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Единственное место, где формируется имя sequence пула и сдвигается курсор выдачи (rid). */
@Service
public class SequenceService {

    private static final int MAX_SEQUENCE_LENGTH = 25;

    private final H2Template jdbc;

    @Autowired
    public SequenceService(H2Template jdbc) {
        this.jdbc = jdbc;
    }

    public String seqPrefix(String env, String pool) {
        String prefix = env + "." + "seq_" + pool;
        return prefix.substring(0, Math.min(prefix.length(), MAX_SEQUENCE_LENGTH));
    }

    public String ridSequence(String env, String pool) {
        return seqPrefix(env, pool) + "_rid";
    }

    public String maxSequence(String env, String pool) {
        return seqPrefix(env, pool) + "_max";
    }

    public long nextRid(String env, String pool) {
        return jdbc.queryForObject("select nextval (?)", new String[]{ridSequence(env, pool)}, Long.class);
    }

    public long nextMax(String env, String pool) {
        return jdbc.queryForObject("select nextval (?)", new String[]{maxSequence(env, pool)}, Long.class);
    }

    /** Ставит курсор выдачи на rid. */
    public void restartRid(String env, String pool, long value) {
        jdbc.update("ALTER SEQUENCE " + ridSequence(env, pool) + " RESTART WITH ?", value);
    }

    /** Последнее выданное значение курсора или null, если не удалось прочитать. */
    public Long currentRid(String env, String pool) {
        try {
            String seq = ridSequence(env, pool);
            return jdbc.queryForObject("SELECT current_value FROM information_schema.sequences WHERE sequence_schema = ? AND sequence_name = ?",
                    Long.class, env.toUpperCase(), seq.substring(env.length() + 1).toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
