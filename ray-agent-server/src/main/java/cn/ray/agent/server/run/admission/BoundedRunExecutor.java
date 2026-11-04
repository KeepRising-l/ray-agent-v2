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
    private final RunQueue<Job> queue;
    // 同一会话串行；不同会话仍竞争全局执行槽。闸门与队列锁分开，避免长事务占锁。
    private final SessionRunGate sessions = new SessionRunGate();
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
        queue = new RunQueue<>(running, Comparator.comparingLong(job -> job.seq));
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

    /** 会话内串行提交；rejected 在底层线程池拒绝任务时通知调用方结束该次流。 */
    public Job submit(Permit permit, String session, String run, long seq, Runnable action, Runnable onRejected) {
        var job = new Job(permit, session, run, seq, action, onRejected);
        // permit从HTTP入口预留，可能已被提前取消；关闭与入队由同一把锁串行。
        synchronized (permit) {
            if (permit.closed.get()) {
                return job;
            }
            permit.job = job;
            schedule(queue.offer(job, this::eligible));
        }
        return job;
    }

    public Job submit(Permit permit, Runnable action) {
        return submit(permit, null, null, 0, action, null);
    }

    /** 准入时先占会话闸门：先落库的运行不会因为 HTTP 线程提交较慢而被后一条抢先执行。 */
    public boolean enter(String session, String run) {
        return session == null || sessions.tryEnter(session, run);
    }

    /** 运行结束/取消/等待提问过期后释放会话闸门，并唤醒被挡住的后续消息。 */
    public void release(String session, String run) {
        if (session == null || run == null) return;
        sessions.release(session, run);
        synchronized (this) {
            schedule(queue.wake(this::eligible));
        }
    }

    private boolean eligible(Job job) {
        return job.session == null || sessions.tryEnter(job.session, job.run);
    }

    private void schedule(List<Job> jobs) {
        for (var job : jobs) {
            try {
                workers.execute(job);
            } catch (RejectedExecutionException error) {
                // 线程池关闭或内部队列满：任务已离开等候区并可能持有会话闸门，必须就地清理。
                job.rejected(error);
            }
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
                    synchronized (BoundedRunExecutor.this) {
                        schedule(queue.remove(value, BoundedRunExecutor.this::eligible));
                    }
                }
            }
        }
    }

    public final class Job implements Runnable {
        private final Permit permit;
        private final String session;
        private final String run;
        private final long seq;
        private final Runnable action;
        private final Runnable onRejected;

        private Job(Permit permit, String session, String run, long seq, Runnable action, Runnable onRejected) {
            this.permit = permit;
            this.session = session;
            this.run = run;
            this.seq = seq;
            this.action = action;
            this.onRejected = onRejected;
        }

        @Override
        public void run() {
            if (permit.closed()) {
                sessions.release(session, run);
                return;
            }
            action.run();
        }

        private void rejected(RejectedExecutionException error) {
            try {
                if (onRejected != null) onRejected.run();
            } catch (RuntimeException ignored) {
                // 通知失败也必须完成资源清理。
            } finally {
                sessions.release(session, run);
                permit.close();
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
