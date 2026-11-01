package cn.ray.agent.server.identity;

import cn.ray.agent.server.admin.ModelSettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 只报告本进程/数据库就绪，不泄露配置，也不为健康检查消耗模型、搜索或Embedding用量。 */
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final ModelSettingsService settings;
    public HealthController(JdbcTemplate jdbc, ModelSettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Boolean>> health() {
        boolean ready;
        try { ready = settings.isReady() && Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)); }
        catch (RuntimeException error) { ready = false; }
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("ready", ready));
    }
}
