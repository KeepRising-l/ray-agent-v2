package cn.ray.agent.server.conversation;

import cn.ray.agent.runtime.model.Message;
import org.springframework.stereotype.Service;
import java.util.*;

/** 每次请求都从持久数据恢复；摘要覆盖连续前缀，不能再把同一范围的原消息叠加一次。 */
@Service
public class SessionRestoreService {
    private final SessionRebuilder rebuilder;
    private final CompactionRecorder recorder;

    public SessionRestoreService(SessionRebuilder rebuilder, CompactionRecorder recorder) {
        this.rebuilder = rebuilder;
        this.recorder = recorder;
    }

    public View restore(String owner, String session) {
        var turns = rebuilder.turns(owner, session);
        var saved = recorder.load(session);
        int covered = 0;
        boolean valid = saved != null;
        if (saved != null) {
            int n = saved.range().runIds().size();
            valid = n <= turns.size() && turns.subList(0,n).stream().map(SessionRebuilder.Turn::runId).toList().equals(saved.range().runIds())
                    && SessionRebuilder.hash(turns.subList(0,n)).equals(saved.range().sourceHash());
            if (valid) { covered = n; }
        }
        return new View(turns, saved, valid, covered);
    }

    /** 摘要作为USER数据投影，不能提升到SYSTEM权限，也不保留可复用的K/W引用标记。 */
    public static List<Message> projection(String summary, List<SessionRebuilder.Turn> remaining) {
        var messages = new ArrayList<Message>();
        messages.addAll(cn.ray.agent.runtime.context.CompactionEngine.summaryMessages(summary));
        remaining.forEach(turn -> messages.addAll(turn.messages()));
        return List.copyOf(messages);
    }

    public record View(List<SessionRebuilder.Turn> turns, CompactionRecorder.Saved saved, boolean valid, int covered) {
        public List<Message> messages() {
            return projection(valid && saved != null ? saved.summary() : "", turns.subList(covered, turns.size()));
        }
    }
}
