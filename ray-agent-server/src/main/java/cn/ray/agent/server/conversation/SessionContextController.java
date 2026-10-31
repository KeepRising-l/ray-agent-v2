package cn.ray.agent.server.conversation;

import org.springframework.web.bind.annotation.*;

/** 本机上下文诊断：返回当前投影用于对照重启，不改变或删除原始历史。 */
@RestController
public class SessionContextController {
    private final SessionRestoreService restore;
    private final LocalOwner owner;

    public SessionContextController(SessionRestoreService restore, LocalOwner owner) {
        this.restore = restore;
        this.owner = owner;
    }

    @GetMapping("/api/sessions/{id}/context")
    public State view(@PathVariable String id) {
        var view = restore.restore(owner.id(), id);
        return new State(view, view.messages());
    }
    public record State(SessionRestoreService.View source, java.util.List<cn.ray.agent.runtime.model.Message> projection) { }
}
