package cn.ray.agent.server.run.dispatch;

import java.util.*;
import java.util.function.Predicate;

/**
 * 单进程等候区；锁只保护队列集合，不在锁内订阅模型、落库或调用用户回调。
 * 按调用方给定的顺序插入并选择任务，用于实现“同一会话串行、不同会话并行”。
 */
public final class RunQueue<T> {
    private final int limit;
    private final Comparator<T> order;
    private final LinkedList<T> waiting = new LinkedList<>();
    private final Set<T> running = new HashSet<>();

    public RunQueue(int limit, Comparator<T> order) {
        this.limit = limit;
        this.order = order;
    }

    public synchronized List<T> offer(T job, Predicate<T> eligible) {
        // 按准入序号插入：HTTP 线程的提交顺序可能与数据库提交顺序不同，不能只依赖到达顺序。
        var it = waiting.listIterator();
        boolean inserted = false;
        while (it.hasNext()) {
            if (order.compare(job, it.next()) < 0) {
                it.previous();
                it.add(job);
                inserted = true;
                break;
            }
        }
        if (!inserted) waiting.addLast(job);
        return ready(eligible);
    }

    public synchronized List<T> remove(T job, Predicate<T> eligible) {
        waiting.remove(job);
        running.remove(job);
        return ready(eligible);
    }

    /** 闸门/执行槽释放后重新挑选可执行任务。 */
    public synchronized List<T> wake(Predicate<T> eligible) {
        return ready(eligible);
    }

    private List<T> ready(Predicate<T> eligible) {
        var result = new ArrayList<T>();
        var it = waiting.iterator();
        // 从队头扫描：跳过会话被占用的任务，既保持会话内顺序，也不让长会话堵住其他会话。
        while (running.size() < limit && it.hasNext()) {
            var next = it.next();
            if (!eligible.test(next)) continue;
            it.remove();
            running.add(next);
            result.add(next);
        }
        return result;
    }

    public synchronized int running() { return running.size(); }
    public synchronized int waiting() { return waiting.size(); }
}
