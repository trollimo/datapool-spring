package load.datapool.todo.rest;

import load.datapool.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** История для страницы /statistics.html. Ответ можно кэшировать на интервал сборщика. */
@RestController
@RequestMapping("api/v1")
public class StatisticsController {

    private final StatisticsService statistics;

    @Autowired
    public StatisticsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> statistics(@RequestParam(value = "minutes", defaultValue = "60") int minutes) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(statistics.getIntervalSeconds(), TimeUnit.SECONDS)).body(statistics.series(minutes));
    }
}
