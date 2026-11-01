package cn.ray.agent.server.admin;

import cn.ray.agent.server.task.TaskQueryService;
import cn.ray.agent.server.conversation.SessionQueryService;
import cn.ray.agent.server.run.BoundedRunExecutor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminOverviewController {
    private final AdminAccessPolicy access;
    private final AdminSettingsStore store;
    private final TaskQueryService tasks;
    private final TaskAdminService actions;
    private final AdminOperationService audit;
    private final RunStatisticsService statistics;
    private final SessionQueryService sessions;
    private final BoundedRunExecutor capacity;
    private final RuntimePolicyService policies;
    private final RunSnapshotService snapshots;
    public AdminOverviewController(AdminAccessPolicy access,AdminSettingsStore store,TaskQueryService tasks,TaskAdminService actions,
            AdminOperationService audit,RunStatisticsService statistics,SessionQueryService sessions,BoundedRunExecutor capacity,RuntimePolicyService policies,RunSnapshotService snapshots) {
        this.access=access;this.store=store;this.tasks=tasks;this.actions=actions;this.audit=audit;this.statistics=statistics;this.sessions=sessions;this.capacity=capacity;this.policies=policies;this.snapshots=snapshots;
    }
    @GetMapping("/overview") public Map<String,Object> overview() {
        String owner=access.owner();
        return Map.of("username",access.username(),"capacity",capacity.capacity(),"knowledgeBases",store.jdbc().queryForObject("SELECT COUNT(*) FROM ra_knowledge_base WHERE owner_id=?",Integer.class,owner),
                "documents",store.jdbc().queryForObject("SELECT COUNT(*) FROM ra_document WHERE owner_id=? AND deleted_at IS NULL",Integer.class,owner));
    }
    @GetMapping("/tasks") public List<TaskQueryService.Task> tasks(){return tasks.list(access.owner());}
    @GetMapping("/sessions") public List<SessionQueryService.View> sessions(){return sessions.list(access.owner());}
    @PostMapping("/tasks/{kind}/{id}/actions") public Map<String,Object> action(@PathVariable String kind,@PathVariable String id,@RequestBody TaskAdminService.Action input) { return actions.act(access.owner(),access.username(),kind,id,input); }
    @GetMapping("/audit") public List<Map<String,Object>> audit(){return audit.list(access.owner());}
    @GetMapping("/policy") public RuntimePolicyService.Policy policy(){access.owner();return policies.current();}
    @PutMapping("/policy") public RuntimePolicyService.Policy policy(@RequestBody RuntimePolicyService.Policy value){return policies.update(value,access.owner(),access.username());}
    @GetMapping("/statistics") public Map<String,Object> statistics(@RequestParam(defaultValue="0") long since,@RequestParam(required=false) Long until){return statistics.report(access.owner(),since,until==null?System.currentTimeMillis():until);}
    @GetMapping("/runs/{id}/snapshot") public RunSnapshotService.Snapshot snapshot(@PathVariable String id){return snapshots.get(access.owner(),id);}
}
