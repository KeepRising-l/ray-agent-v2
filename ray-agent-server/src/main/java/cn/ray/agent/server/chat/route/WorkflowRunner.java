package cn.ray.agent.server.chat.route;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.context.TokenEstimator;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.tool.ToolContext;
import cn.ray.agent.server.chat.ChatWorkflow;
import cn.ray.agent.server.rag.answer.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** AUTO 的有界串行编排器。业务步骤不另起线程等待自己，取消同时关闭当前模型/子工作流和工具。 */
@Component
public class WorkflowRunner {
    // 根阶段会等待AUTO子阶段，必须使用不同的有界池，避免占满后互相等待。
    private final ExecutorService workers = pool("auto-workflow");
    private final ExecutorService rootWorkers = pool("context-workflow");

    private static ExecutorService pool(String name) {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @FunctionalInterface
    public interface Plan {
        void execute(Session session) throws Exception;
    }

    public LlmAdapter bind(LlmAdapter model, RunBudget budget, ChatWorkflow.Events events, PlanFactory factory) {
        return bind(model, budget, events, factory, "ROUTE");
    }

    /** RUN用于整个请求计量，ROUTE用于AUTO内部轨迹，两层不相互覆盖估算。 */
    public LlmAdapter bind(LlmAdapter model, RunBudget budget, ChatWorkflow.Events events, PlanFactory factory, String prefix) {
        return request -> {
            var once = new AtomicBoolean();
            return subscriber -> {
                var session = new Session(model, request, budget, events, subscriber, factory.create(request), prefix);
                if (!once.compareAndSet(false, true)) {
                    session.cancel();
                    subscriber.onSubscribe(session);
                    subscriber.onError(new IllegalStateException("one subscriber per AUTO request"));
                } else {
                    subscriber.onSubscribe(session);
                }
            };
        };
    }

    @FunctionalInterface
    public interface PlanFactory {
        Plan create(GenerateRequest request);
    }

    public final class Session implements Flow.Subscription, Runnable {
        public final GenerateRequest base;
        public final RunBudget budget;
        public final ChatWorkflow.Events events;
        public final ToolContext cancellation = new ToolContext();
        /** 所有实际模型调用都经过计量器，分类和子工作流也共享次数上限。 */
        public final LlmAdapter model;
        private final Flow.Subscriber<? super StreamChunk> downstream;
        private final Plan plan;
        private final String prefix;
        private volatile boolean cancelled;
        private volatile boolean terminal;
        private volatile Flow.Subscription upstream;
        private volatile Thread worker;
        private boolean started;
        private long demand;
        private long deadline;
        private int modelCalls;
        private int estimatedInput;
        private Integer inputUsage = 0;
        private Integer outputUsage = 0;

        private Session(LlmAdapter original, GenerateRequest base, RunBudget budget, ChatWorkflow.Events events,
                        Flow.Subscriber<? super StreamChunk> downstream, Plan plan, String prefix) {
            this.base = base;
            this.budget = budget;
            this.events = events;
            this.downstream = downstream;
            this.plan = plan;
            this.prefix = prefix;
            this.model = request -> {
                remaining();
                Map<String, Object> context;
                synchronized (this) {
                    if (++modelCalls > budget.modelSteps()) {
                        throw new GroundedAnswerService.Failure("BUDGET_EXCEEDED", "AUTO 模型调用预算已耗尽");
                    }
                    estimatedInput += request.messages().stream().mapToInt(new TokenEstimator()::estimate).sum();
                    context = Map.of("modelCalls", modelCalls, "estimatedInputTotal", estimatedInput);
                }
                // 落库/发 SSE 会取得外层运行锁，不能持有订阅锁调用，避免与取消反向等待。
                event(prefix + "_CONTEXT_SELECTED", context);
                return subscriber -> original.generate(request).subscribe(new Flow.Subscriber<StreamChunk>() {
                    private boolean accounted;
                    public void onSubscribe(Flow.Subscription value) { subscriber.onSubscribe(value); }
                    public void onNext(StreamChunk value) {
                        if (value instanceof StreamChunk.Finished finished && !accounted) {
                            accounted = true;
                            var usage = finished.usage();
                            synchronized (Session.this) {
                                inputUsage = sum(inputUsage, usage == null ? null : usage.inputTokens());
                                outputUsage = sum(outputUsage, usage == null ? null : usage.outputTokens());
                            }
                        }
                        if (value instanceof StreamChunk.Finished finished && prefix.equals("RUN")) {
                            var usage = finished.usage();
                            var data = new HashMap<String, Object>();
                            data.put("inputTokens", usage == null ? null : usage.inputTokens());
                            data.put("outputTokens", usage == null ? null : usage.outputTokens());
                            event("RUN_MODEL_ACCOUNTED", data);
                        }
                        subscriber.onNext(value);
                    }
                    public void onError(Throwable error) {
                        synchronized (Session.this) {
                            inputUsage = null;
                            outputUsage = null;
                        }
                        if (prefix.equals("RUN")) {
                            var data = new HashMap<String, Object>();
                            data.put("inputTokens", null);
                            data.put("outputTokens", null);
                            event("RUN_MODEL_ACCOUNTED", data);
                        }
                        subscriber.onError(error);
                    }
                    public void onComplete() {
                        if (!accounted) {
                            synchronized (Session.this) {
                                inputUsage = null;
                                outputUsage = null;
                            }
                        }
                        subscriber.onComplete();
                    }
                });
            };
        }

        @Override
        public synchronized void request(long n) {
            if (terminal || cancelled) {
                return;
            }
            if (n <= 0) {
                terminal = true;
                cancel();
                downstream.onError(new IllegalArgumentException("positive demand required"));
                return;
            }
            demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
            notifyAll();
            if (!started) {
                started = true;
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget.timeoutMs());
                try {
                    (prefix.equals("RUN") ? rootWorkers : workers).execute(this);
                } catch (RejectedExecutionException e) {
                    terminal = true;
                    downstream.onError(new GroundedAnswerService.Failure("BUSY", "AUTO 运行容量已满，请稍后重试"));
                }
            }
        }

        @Override
        public void cancel() {
            synchronized (this) {
                cancelled = true;
                notifyAll();
            }
            cancellation.cancel();
            var value = upstream;
            if (value != null) {
                value.cancel();
            }
            Thread thread = worker;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            worker = Thread.currentThread();
            try {
                remaining();
                plan.execute(this);
                event(prefix + "_FINISHED", Map.of("modelCalls", modelCalls));
                emit(new StreamChunk.Finished(StreamChunk.FinishReason.STOP, new TokenUsage(inputUsage, outputUsage)));
                if (!cancelled) {
                    terminal = true;
                    downstream.onComplete();
                }
            } catch (Exception e) {
                if (!cancelled && !terminal) {
                    terminal = true;
                    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                    downstream.onError(cause instanceof GroundedAnswerService.Failure || cause instanceof ReactLoopAgent.Failure || cause instanceof UserQuestionRequired
                            ? cause : new GroundedAnswerService.Failure("AUTO_FAILED", "AUTO 处理失败，请检查模型或资料配置"));
                }
            } finally {
                if (upstream != null) {
                    upstream.cancel();
                }
                cancellation.cancel();
                worker = null;
            }
        }

        /** 等待一个完整阶段；最终文本可流式转发，Finished 统一由外层输出并累计真实模型用量。 */
        public StepResult collect(LlmAdapter adapter, GenerateRequest request, boolean stream) throws Exception {
            remaining();
            var result = new StepResult();
            var done = new CompletableFuture<StepResult>();
            adapter.generate(request).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription value) {
                    upstream = value;
                    if (cancelled) {
                        value.cancel();
                    } else {
                        value.request(Long.MAX_VALUE);
                    }
                }
                public void onNext(StreamChunk value) {
                    if (cancelled || done.isDone()) {
                        return;
                    }
                    try {
                        if (value instanceof StreamChunk.ToolCallDelta) {
                            throw new GroundedAnswerService.Failure("TOOL_FORBIDDEN", "固定编排阶段不接受模型工具调用");
                        }
                        result.accept(value);
                        if (stream && value instanceof StreamChunk.TextDelta) {
                            emit(value);
                        }
                    } catch (Exception e) {
                        done.completeExceptionally(e);
                        upstream.cancel();
                    }
                }
                public void onError(Throwable error) { done.completeExceptionally(error); }
                public void onComplete() { done.complete(result); }
            });
            try {
                var complete = done.get(remaining(), TimeUnit.MILLISECONDS);
                complete.message();
                if (complete.finished().reason() != StreamChunk.FinishReason.STOP) {
                    throw new GroundedAnswerService.Failure("MODEL_INCOMPLETE", "模型未完整返回 AUTO 处理结果");
                }
                return complete;
            } catch (TimeoutException e) {
                throw new GroundedAnswerService.Failure("TIMEOUT", "AUTO 运行超时");
            }
        }

        public void text(String text) throws InterruptedException {
            emit(new StreamChunk.TextDelta(text));
        }

        public void event(String type, Map<String, Object> data) {
            remaining();
            events.metadata().accept(new RagStreamEventMapper.Event(type, data));
        }

        public long remaining() {
            cancellation.checkCancelled();
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (cancelled) {
                throw new CancellationException();
            }
            if (left <= 0) {
                throw new GroundedAnswerService.Failure("TIMEOUT", "AUTO 运行超时");
            }
            return left;
        }

        public int remainingModelCalls() {
            return budget.modelSteps() - modelCalls;
        }

        private void emit(StreamChunk value) throws InterruptedException {
            synchronized (this) {
                while (!cancelled && demand == 0) {
                    wait(remaining());
                }
                remaining();
                demand--;
            }
            downstream.onNext(value);
        }

        private Integer sum(Integer a, Integer b) {
            return a == null || b == null ? null : Math.addExact(a, b);
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        rootWorkers.shutdownNow();
    }
}
