package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.context.ContextAssembler;
import cn.ray.agent.runtime.context.ContextBudget;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

/** 模型 → 工具 → 观察 → 模型。每个运行串行续步，不自动重放已经执行的工具。 */
public final class ReactLoopAgent implements AutoCloseable {
    private final ToolRegistry registry;
    private final ToolExecutor tools;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "agent-loop");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ReactLoopAgent(ToolRegistry registry, ToolExecutor tools) {
        this.registry = registry;
        this.tools = tools;
    }

    /** 以普通适配器契约包住多步循环，沿用现有调度与取消链路。允许名单同时限制定义与执行。 */
    public LlmAdapter bind(LlmAdapter model, Set<String> allowed, RunBudget budget, AgentEventSink sink) {
        Set<String> permissions = Set.copyOf(allowed);
        return request -> {
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            return subscriber -> {
                Loop loop = new Loop(subscriber, model, request, permissions, budget, sink);
                if (!once.compareAndSet(false, true)) {
                    loop.cancel();
                    subscriber.onSubscribe(loop);
                    subscriber.onError(new IllegalStateException("generation permits one subscriber"));
                } else {
                    subscriber.onSubscribe(loop);
                }
            };
        };
    }

    private final class Loop implements Flow.Subscription, Runnable {
        private final Flow.Subscriber<? super StreamChunk> subscriber;
        private final LlmAdapter model;
        private final GenerateRequest initial;
        private final Set<String> allowed;
        private final RunBudget budget;
        private final AgentEventSink sink;
        private final ToolContext cancellation = new ToolContext();
        // 相同 URL 在本次运行复用同一 W 编号；编号不跨运行复用。
        private final Map<String, ToolResult.Source> sources = new LinkedHashMap<>();
        private final Set<String> callIds = new HashSet<>();
        private boolean started;
        private volatile boolean cancelled;
        private volatile boolean terminal;
        private long demand;
        private long endsAt;
        // 只指向当前一步的模型订阅；外层 run 取消时同时关闭它和工具资源。
        private volatile Flow.Subscription upstream;
        private volatile Thread worker;
        private Integer inputUsage = 0;
        private Integer outputUsage = 0;
        private boolean emittedText;

        private Loop(Flow.Subscriber<? super StreamChunk> subscriber, LlmAdapter model,
                     GenerateRequest initial, Set<String> allowed, RunBudget budget, AgentEventSink sink) {
            this.subscriber = subscriber;
            this.model = model;
            this.initial = initial;
            this.allowed = allowed;
            this.budget = budget;
            this.sink = sink;
        }

        @Override
        public synchronized void request(long count) {
            if (cancelled || terminal) {
                return;
            }
            if (count <= 0) {
                terminal = true;
                cancel();
                subscriber.onError(new IllegalArgumentException("demand must be positive"));
                return;
            }
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            notifyAll();
            if (!started) {
                started = true;
                endsAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget.timeoutMs());
                try {
                    workers.execute(this);
                } catch (RejectedExecutionException e) {
                    terminal = true;
                    subscriber.onError(new Failure("BUSY", "运行容量已满，请稍后重试"));
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
            Flow.Subscription value = upstream;
            if (value != null) {
                value.cancel();
            }
            Thread valueWorker = worker;
            if (valueWorker != null && valueWorker != Thread.currentThread()) {
                valueWorker.interrupt();
            }
        }

        @Override
        public void run() {
            worker = Thread.currentThread();
            try {
                cancellation.checkCancelled();
                List<Message> messages = new ArrayList<>(initial.messages());
                var definitions = registry.definitions(allowed);
                int definitionCost = definitions.stream().mapToInt(item -> (item.name() + item.description()
                        + item.parametersSchemaJson()).getBytes(StandardCharsets.UTF_8).length + 16).sum();
                int calls = 0;
                for (int step = 1; step <= budget.modelSteps(); step++) {
                    remaining();
                    List<Message> selected;
                    try {
                        selected = new ContextAssembler().assemble(messages,
                                new ContextBudget(budget.inputTokens() - definitionCost, 1024)).messages();
                    } catch (IllegalArgumentException e) {
                        throw new Failure("CONTEXT_BUDGET", "工具结果超出上下文预算，请缩小问题范围");
                    }
                    sink.accept(new AgentEventSink.Event("MODEL_STARTED", step, "", "", "", 0, List.of()));
                    StepResult result = generate(new GenerateRequest(selected, initial.options(), definitions));
                    Message response = result.message();
                    addUsage(result.finished().usage());
                    sink.accept(new AgentEventSink.Event("MODEL_FINISHED", step, "", "",
                            result.finished().reason().name(), 0, List.of()));
                    if (response.toolCalls().isEmpty()) {
                        validateCitations(response.content());
                        emit(new StreamChunk.Finished(result.finished().reason(), new TokenUsage(inputUsage, outputUsage)));
                        if (!cancelled) {
                            terminal = true;
                            subscriber.onComplete();
                        }
                        return;
                    }
                    // 最后一步仍提出工具调用时不再执行，避免执行后没有预算把结果交回模型。
                    if (step == budget.modelSteps() || calls + response.toolCalls().size() > budget.toolCalls()) {
                        throw new Failure("BUDGET_EXCEEDED", "模型或工具调用预算已耗尽，请缩小问题范围");
                    }
                    messages = new ArrayList<>(selected);
                    messages.add(response);
                    for (ToolCall call : response.toolCalls()) {
                        if (!callIds.add(call.id())) {
                            throw new Failure("PROTOCOL_ERROR", "模型返回了重复工具调用编号");
                        }
                        remaining();
                        calls++;
                        long startedAt = System.nanoTime();
                        sink.accept(new AgentEventSink.Event("TOOL_STARTED", step, call.id(), call.name(), "", 0, List.of()));
                        ToolResult observation = tools.execute(call, allowed, cancellation,
                                Math.min(remaining(), budget.toolTimeoutMs()));
                        remaining();
                        List<ToolResult.Source> evidence = new ArrayList<>();
                        for (var source : observation.sources()) {
                            var assigned = sources.computeIfAbsent(source.url(), url -> new ToolResult.Source(
                                    "W" + (sources.size() + 1), source.title(), url, source.kind()));
                            evidence.add(new ToolResult.Source(assigned.id(), source.title(), source.url(), source.kind()));
                        }
                        observation = new ToolResult(observation.success(), observation.code(), observation.content(), evidence);
                        sink.accept(new AgentEventSink.Event(observation.success() ? "TOOL_SUCCEEDED" : "TOOL_FAILED",
                                step, call.id(), call.name(), observation.code(),
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), evidence));
                        messages.add(new Message(Message.Role.TOOL, mapper.writeValueAsString(observation), List.of(), call.id()));
                    }
                }
            } catch (Exception e) {
                if (!cancelled && !terminal) {
                    terminal = true;
                    subscriber.onError(e instanceof Failure ? e : new Failure("MODEL_FAILED", "模型调用失败或返回不完整"));
                }
            } finally {
                Flow.Subscription value = upstream;
                if (value != null) {
                    value.cancel();
                }
                cancellation.cancel();
                worker = null;
            }
        }

        private StepResult generate(GenerateRequest request) throws Exception {
            StepResult result = new StepResult();
            CompletableFuture<StepResult> complete = new CompletableFuture<>();
            boolean separate = emittedText;
            model.generate(request).subscribe(new Flow.Subscriber<>() {
                private boolean firstText = true;
                @Override
                public void onSubscribe(Flow.Subscription value) {
                    upstream = value;
                    if (cancelled) {
                        value.cancel();
                    } else {
                        value.request(Long.MAX_VALUE);
                    }
                }

                @Override
                public void onNext(StreamChunk chunk) {
                    if (cancelled || complete.isDone()) {
                        return;
                    }
                    try {
                        result.accept(chunk);
                        if (chunk instanceof StreamChunk.TextDelta) {
                            if (firstText && separate) {
                                emit(new StreamChunk.TextDelta("\n\n"));
                            }
                            firstText = false;
                            emittedText = true;
                            emit(chunk);
                        }
                    } catch (Exception e) {
                        complete.completeExceptionally(e);
                        upstream.cancel();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    complete.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    complete.complete(result);
                }
            });
            try {
                return complete.get(remaining(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new Failure("TIMEOUT", "运行时间预算已耗尽");
            }
        }

        private void emit(StreamChunk chunk) throws InterruptedException {
            synchronized (this) {
                while (demand == 0 && !cancelled) {
                    wait(remaining());
                }
                if (cancelled) {
                    throw new CancellationException();
                }
                demand--;
            }
            subscriber.onNext(chunk);
        }

        private long remaining() {
            cancellation.checkCancelled();
            long left = TimeUnit.NANOSECONDS.toMillis(endsAt - System.nanoTime());
            if (left <= 0) {
                throw new Failure("TIMEOUT", "运行时间预算已耗尽");
            }
            return left;
        }

        private void addUsage(TokenUsage usage) {
            inputUsage = sum(inputUsage, usage == null ? null : usage.inputTokens());
            outputUsage = sum(outputUsage, usage == null ? null : usage.outputTokens());
        }

        private Integer sum(Integer total, Integer next) {
            return total == null || next == null ? null : Math.addExact(total, next);
        }

        private void validateCitations(String text) {
            var references = Pattern.compile("\\[W(\\d+)\\]").matcher(text);
            while (references.find()) {
                String id = "W" + references.group(1);
                if (sources.values().stream().noneMatch(source -> source.id().equals(id))) {
                    throw new Failure("INVALID_CITATION", "回答引用了本次运行不存在的网页来源");
                }
            }
            if (!sources.isEmpty() && !Pattern.compile("\\[W\\d+\\]").matcher(text).find()) {
                throw new Failure("MISSING_CITATION", "模型使用网页工具后没有提供来源编号");
            }
        }
    }

    /** 只有经过整理的公开错误才能直接展示给用户。 */
    public static final class Failure extends RuntimeException {
        private final String code;

        public Failure(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
