package load.datapool.todo.rest;

import load.datapool.service.PoolDiagnosticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("api/v1")
public class PoolsController {

    private final PoolDiagnosticsService diagnostics;

    @Autowired
    public PoolsController(PoolDiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Все пулы; первыми те, у которых счётчики в БД и в локере расходятся. */
    @GetMapping("/pools")
    public List<Map<String, Object>> pools() {
        return diagnostics.listPools();
    }

    /** Исправить/сбросить счётчик одного пула. */
    @PostMapping("/pools/repair")
    public ResponseEntity<Map<String, Object>> repair(@RequestParam("env") String env, @RequestParam("pool") String pool) {
        return ResponseEntity.ok(diagnostics.repair(env, pool));
    }

    /** Массовое исправление: тело — список полных имён ENV.POOL. */
    @PostMapping("/pools/repair-batch")
    public List<Map<String, Object>> repairBatch(@RequestBody List<String> pools) {
        return diagnostics.repairAll(pools);
    }
}
