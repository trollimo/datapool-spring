package load.datapool.todo.rest;

import load.datapool.prometheus.Exporter;
import load.datapool.service.PoolException;
import load.datapool.service.PoolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Supplier;

/** REST API пулов. Контроллер только принимает параметры, вызывает PoolService и пишет метрики. */
@RestController
@RequestMapping("api/v1")
public class DatapoolController {

    private final PoolService service;
    private final Exporter exp;

    @Autowired
    public DatapoolController(PoolService service, Exporter exp) {
        this.service = service;
        this.exp = exp;
    }

    @GetMapping(path = "/get-next-value")
    public ResponseEntity<String> getNextData(@RequestParam(value = "env", defaultValue = "load") String env,
                                              @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                              @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        return run(env, pool, "get-next-value", () -> service.nextValue(env, pool, locked));
    }

    @PostMapping(path = "/put-value")
    public ResponseEntity<String> putData(@RequestParam(value = "env", defaultValue = "load") String env,
                                          @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                          @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                          @RequestBody String text) {
        return run(env, pool, "put-value", () -> service.put(env, pool, searchKey, text));
    }

    @PostMapping(path = "/unlock")
    public ResponseEntity<String> unlockData(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                             @RequestParam(value = "rid", defaultValue = "-1") String sRid,
                                             @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                             @RequestParam(value = "unlock-all", defaultValue = "false") boolean unlockAll) {
        return run(env, pool, "unlock", () -> service.unlock(env, pool, sRid, searchKey, unlockAll));
    }

    @GetMapping(path = "/search-by-key")
    public ResponseEntity<String> getBySearchKey(@RequestParam(value = "env", defaultValue = "load") String env,
                                                 @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                                 @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                                 @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        return run(env, pool, "search-by-key", () -> service.searchByKey(env, pool, searchKey, locked));
    }

    @PostMapping(path = "/upload-csv-as-json")
    public ResponseEntity<String> uploadCSV(@RequestParam(value = "env", defaultValue = "load") String env,
                                            @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                            @RequestParam(value = "delimiter", defaultValue = ",") String delim,
                                            @RequestParam(value = "override", defaultValue = "false") boolean override,
                                            @RequestParam(value = "with-headers", defaultValue = "true") boolean withHeaders,
                                            @RequestParam("file") MultipartFile file) {
        return run(env, pool, "upload-csv-as-json", () -> {
            try {
                return service.uploadCsv(env, pool, delim, override, withHeaders, file.getSize(), file.getInputStream());
            } catch (IOException e) {
                throw new PoolException("File parse exception: " + e.getMessage(), "File parse exception");
            }
        });
    }

    @DeleteMapping(path = "/drop-pool")
    public ResponseEntity<String> deletePool(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool) {
        return run(env, pool, "drop-pool", () -> service.drop(env, pool));
    }

    // reset sequence to 1 (zero point of pool)
    @GetMapping(path = "/resetseq")
    public ResponseEntity<String> resetseq(@RequestParam(value = "env", defaultValue = "load") String env,
                                           @RequestParam(value = "pool", defaultValue = "testpool") String pool) {
        return run(env, pool, "resetseq", () -> service.resetSequence(env, pool));
    }

    /** Выполняет сценарий, пишет метрику (успех — без details) и превращает PoolException в ответ. */
    private ResponseEntity<String> run(String env, String pool, String method, Supplier<String> action) {
        Instant start = Instant.now();
        try {
            String body = action.get();
            exp.incRequestsAndLatency(env, pool, method, null, start);
            return ResponseEntity.ok(body);
        } catch (PoolException e) {
            if (!e.isBadName()) exp.incRequestsAndLatency(env, pool, method, e.getDetail(), start);
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        }
    }
}
