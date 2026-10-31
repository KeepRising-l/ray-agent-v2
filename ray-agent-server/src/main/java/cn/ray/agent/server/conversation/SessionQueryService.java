package cn.ray.agent.server.conversation;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SessionQueryService {
    private final ConversationRepository store;
    private final SessionRegistry registry;
    public SessionQueryService(ConversationRepository store, SessionRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** 历史状态来自数据库；attached仅表示当前进程是否登记了活动SSE。 */
    public List<View> list(String owner) {
        return store.sessions(owner).stream().map(session -> {
            var runs = store.runs(owner, session.id());
            var latest = runs.isEmpty() ? null : runs.get(0);
            return new View(session.id(), session.title(), session.mode(), latest == null ? null : latest.id(),
                    latest == null ? "EMPTY" : latest.status(), latest != null && registry.attached(owner, latest.id()));
        }).toList();
    }

    public record View(String sessionId, String title, String mode, String latestRunId, String status, boolean attached) { }
}
