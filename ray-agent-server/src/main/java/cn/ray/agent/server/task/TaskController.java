package cn.ray.agent.server.task;

import cn.ray.agent.server.conversation.*;
import cn.ray.agent.server.run.BoundedRunExecutor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class TaskController {
    private final TaskQueryService tasks;
    private final SessionQueryService sessions;
    private final BoundedRunExecutor executor;
    private final LocalOwner owner;

    public TaskController(TaskQueryService tasks, SessionQueryService sessions,
                          BoundedRunExecutor executor, LocalOwner owner) {
        this.tasks = tasks;
        this.sessions = sessions;
        this.executor = executor;
        this.owner = owner;
    }

    @GetMapping("/api/tasks")
    public List<TaskQueryService.Task> tasks() {
        return tasks.list(owner.id());
    }

    @GetMapping("/api/tasks/capacity")
    public BoundedRunExecutor.Capacity capacity() {
        return executor.capacity();
    }

    @GetMapping("/api/tasks/documents/{id}/attempts")
    public List<Map<String, Object>> attempts(@PathVariable String id) {
        return tasks.attempts(owner.id(), id);
    }

    @GetMapping("/api/sessions/status")
    public List<SessionQueryService.View> sessions() {
        return sessions.list(owner.id());
    }
}
