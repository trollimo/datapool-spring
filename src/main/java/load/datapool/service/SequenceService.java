package load.datapool.service;

import load.datapool.repository.PoolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Курсор выдачи пула (sequence _rid) и счётчик вставок (_max). Имена и SQL — в PoolRepository. */
@Service
public class SequenceService {

    private final PoolRepository repository;

    @Autowired
    public SequenceService(PoolRepository repository) {
        this.repository = repository;
    }

    public String seqPrefix(String env, String pool) { return repository.seqPrefix(env, pool); }

    public String ridSequence(String env, String pool) { return repository.ridSequence(env, pool); }

    public String maxSequence(String env, String pool) { return repository.maxSequence(env, pool); }

    public long nextRid(String env, String pool) { return repository.nextval(ridSequence(env, pool)); }

    public long nextMax(String env, String pool) { return repository.nextval(maxSequence(env, pool)); }

    /** Ставит курсор выдачи на rid. */
    public void restartRid(String env, String pool, long value) { repository.restartSequence(ridSequence(env, pool), value); }

    /** Последнее выданное значение курсора или null, если не удалось прочитать. */
    public Long currentRid(String env, String pool) {
        String seq = ridSequence(env, pool);
        return repository.currentSequenceValue(env.toUpperCase(), seq.substring(env.length() + 1).toUpperCase());
    }
}
