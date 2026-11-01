package cn.ray.agent.server.admin;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 统计按owner和run创建时间取窗口，未知usage保留null；事件尝试次数不等于成功工具数。 */
@Service
public class RunStatisticsService {
    private final JdbcTemplate jdbc;
    public RunStatisticsService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> report(String owner,long since,long until) {
        if(since<0 || until<since || until>System.currentTimeMillis()+60000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"统计时间范围无效");
        var result=new LinkedHashMap<String,Object>();
        result.put("since",since);result.put("until",until);result.put("basis","run.created_at，当前owner，包含该窗口创建且仍活动的run");
        result.put("totals",jdbc.queryForMap("SELECT COUNT(*) AS runs,SUM(input_tokens) AS known_input_tokens,SUM(output_tokens) AS known_output_tokens,COUNT(*) FILTER(WHERE input_tokens IS NULL OR output_tokens IS NULL) AS runs_with_missing_usage,AVG(ended_at-created_at) FILTER(WHERE ended_at IS NOT NULL) AS completed_average_total_ms FROM ra_run WHERE owner_id=? AND created_at>=? AND created_at<=?",owner,since,until));
        result.put("states",jdbc.queryForList("SELECT status,COUNT(*) AS count FROM ra_run WHERE owner_id=? AND created_at>=? AND created_at<=? GROUP BY status ORDER BY status",owner,since,until));
        result.put("models",jdbc.queryForList("SELECT channel_id,configured_model,COUNT(*) AS count FROM ra_run WHERE owner_id=? AND created_at>=? AND created_at<=? GROUP BY channel_id,configured_model ORDER BY channel_id,configured_model",owner,since,until));
        result.put("events",jdbc.queryForList("SELECT e.type,COUNT(*) AS count FROM ra_event e JOIN ra_run r ON r.id=e.run_id WHERE r.owner_id=? AND r.created_at>=? AND r.created_at<=? AND (e.type LIKE 'TOOL_%' OR e.type IN ('ROUTE_DECIDED','COMPACTION_APPLIED','RAG_REFUSED')) GROUP BY e.type ORDER BY e.type",owner,since,until));
        result.put("routes",jdbc.queryForList("SELECT e.detail::jsonb ->> 'target' AS target,COUNT(*) AS count FROM ra_event e JOIN ra_run r ON r.id=e.run_id WHERE r.owner_id=? AND r.created_at>=? AND r.created_at<=? AND e.type='ROUTE_DECIDED' GROUP BY target ORDER BY target",owner,since,until));
        return result;
    }
}
