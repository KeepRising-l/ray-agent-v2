package cn.ray.agent.server.run.admission;

import cn.ray.agent.server.run.dispatch.RunQueue;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 入库前预留容量，终态/暂停/取消只释放一次；执行槽持续到异步Flow结束，不按订阅方法返回就释放。 */
@Component
public class BoundedRunExecutor {
    private final RunConcurrencyPolicy policy;
    private final RunQueue queue;
    private final ThreadPoolExecutor workers;
    // 预留数包含尚未提交、排队和执行中的运行；等待用户时释放预留。
    private final Map<String, Integer> owners = new HashMap<>();
    private int admitted;
    private long rejected;
    private boolean closing;

    public BoundedRunExecutor(@Value("${ray.run.max-concurrent:2}") int running,
                              @Value("${ray.run.queue-capacity:8}") int queued,
                              @Value("${ray.run.max-per-owner:0}") int perOwner) {
        policy = new RunConcurrencyPolicy(running, queued, perOwner == 0 ? running + queued : perOwner);
        queue = new RunQueue(running);
        workers = new ThreadPoolExecutor(running, running, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(running + queued), task -> {
                    var thread = new Thread(task, "run-dispatch");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized Permit reserve(String owner) {
        if (closing || admitted >= policy.running() + policy.queued()
                || owners.getOrDefault(owner, 0) >= policy.perOwner()) {
            rejected++;
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "运行容量已满，请等待现有运行完成或取消后重试");
        }
        admitted++;
        owners.merge(owner, 1, Integer::sum);
        return new Permit(owner);
    }

    public Job submit(Permit permit, Runnable action) {
        var job = new Job(permit, action);
        // permit从HTTP入口预留，可能已被提前取消；关闭与入队由同一把锁串行。
        synchronized (permit) {
            if (permit.closed.get()) {
                return job;
            }
            permit.job = job;
            schedule(queue.offer(job));
        }
        return job;
    }

    private void schedule(List<Runnable> jobs) {
        for (var job : jobs) {
            workers.execute(job);
        }
    }

    public synchronized Capacity capacity() {
        return new Capacity(policy.running(), policy.queued(), queue.running(), queue.waiting(), admitted, rejected);
    }

    public final class Permit implements AutoCloseable {
        private final String owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Job job;
        private Permit(String owner) {
            this.owner = owner;
        }

        public boolean closed() {
            return closed.get();
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                synchronized (BoundedRunExecutor.this) {
                    admitted--;
                    owners.compute(owner, (key, value) -> value == null || value <= 1 ? null : value - 1);
                }
                Job value = job;
                if (value != null) {
                    // 被取消的运行可能尚未取得实际线程；清除线程池中的占位，避免快速取消撑满第二层队列。
                    workers.remove(value);
                    schedule(queue.remove(value));
                }
            }
        }
    }

    public final class Job implements Runnable {
        private final Permit permit;
        private final Runnable action;
        private Job(Permit permit, Runnable action) {
            this.permit = permit;
            this.action = action;
        }

        @Override
        public void run() {
            if (!permit.closed()) {
                action.run();
            }
        }
    }

    @PreDestroy
    public void close() {
        synchronized (this) {
            closing = true;
        }
        workers.shutdownNow();
    }

    public record Capacity(int runningLimit, int queueLimit, int running, int queued, int admitted, long rejected) { }
}
