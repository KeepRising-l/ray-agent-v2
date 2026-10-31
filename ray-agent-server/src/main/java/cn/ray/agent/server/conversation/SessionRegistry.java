package cn.ray.agent.server.conversation;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** 只登记本进程的活动连接；数据库状态和WAITING_USER检查点不因内存登记消失而丢失。 */
@Component
public class SessionRegistry {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Entry register(String owner, String run) {
        var entry = new Entry(owner, run, System.currentTimeMillis());
        // 唯一运行/一次性回答由数据库事务保证；合法接续可能早于旧SSE清理，允许新段替换登记。
        entries.put(run, entry);
        return entry;
    }

    /** 按对象身份移除，旧SSE的清理回调不能删掉同一run的新接续连接。 */
    public void remove(Entry entry) {
        entries.computeIfPresent(entry.runId(), (key, value) -> value == entry ? null : value);
    }

    public boolean attached(String owner, String run) {
        var entry = entries.get(run);
        return entry != null && entry.ownerId().equals(owner);
    }

    public record Entry(String ownerId, String runId, long attachedAt) { }
}
