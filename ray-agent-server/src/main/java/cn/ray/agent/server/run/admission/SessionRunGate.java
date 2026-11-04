package cn.ray.agent.server.run.admission;

import java.util.HashMap;
import java.util.Map;

/**
 * 单进程内保证同一会话最多一个运行处于“已提交/运行中/等待用户”状态。
 * 同一 runId 的续跑（等待用户补充后回答）允许重入，其余运行按会话串行。
 */
public final class SessionRunGate {
    private final Map<String, String> owners = new HashMap<>();

    public synchronized boolean tryEnter(String session, String run) {
        String owner = owners.get(session);
        if (owner == null) {
            owners.put(session, run);
            return true;
        }
        return owner.equals(run);
    }

    /** 幂等释放；晚到的旧运行不能把后来取得闸门的新运行踢掉。 */
    public synchronized void release(String session, String run) {
        owners.remove(session, run);
    }

    public synchronized boolean ownedBy(String session, String run) {
        return run.equals(owners.get(session));
    }
}
