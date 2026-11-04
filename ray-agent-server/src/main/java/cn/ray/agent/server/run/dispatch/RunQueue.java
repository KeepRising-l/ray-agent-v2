package cn.ray.agent.server.run.dispatch;

import java.util.*;

/** 单进程FIFO；锁只保护队列集合，不在锁内订阅模型、落库或调用用户回调。 */
public final class RunQueue {
    private final int limit;
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private final Set<Runnable> running = new HashSet<>();

    public RunQueue(int limit) { this.limit = limit; }

    public synchronized List<Runnable> offer(Runnable job) {
        waiting.addLast(job);
        return ready();
    }

    public synchronized List<Runnable> remove(Runnable job) {
        waiting.remove(job);
        running.remove(job);
        return ready();
    }

    private List<Runnable> ready() {
        var result = new ArrayList<Runnable>();
        while (running.size() < limit && !waiting.isEmpty()) {
            var next = waiting.removeFirst();
            running.add(next);
            result.add(next);
        }
        return result;
    }

    public synchronized int running() { return running.size(); }
    public synchronized int waiting() { return waiting.size(); }
}
