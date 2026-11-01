package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.agent.StepResult;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.rag.answer.*;
import cn.ray.agent.server.rag.retrieve.RetrievalFilter;
import cn.ray.agent.server.rag.retrieve.*;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** 固定检索—证据判断—生成工作流，完全不进入通用Agent，也不提供任何网页工具。 */
@Component
public class KnowledgeChatWorkflow implements ChatWorkflow {
    private final GroundedAnswerService answers;
    private final RetrievalExperimentPolicy experiments;
    private final CitationValidator validator = new CitationValidator();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "knowledge-chat");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public KnowledgeChatWorkflow(GroundedAnswerService answers) {
        this(answers, RetrievalExperimentPolicy.baseline());
    }

    @Autowired
    public KnowledgeChatWorkflow(GroundedAnswerService answers, RetrievalExperimentPolicy experiments) {
        this.answers = answers;
        this.experiments = experiments;
    }

    @Override
    public LlmAdapter bind(LlmAdapter model, ChatWorkflow.Scope scope, cn.ray.agent.runtime.agent.RunBudget budget, ChatWorkflow.Events events) {
        return bind(model, new RetrievalFilter(scope.ownerId(), scope.knowledgeBaseIds()), budget.timeoutMs(), budget.modelSteps(), events.metadata());
    }

    public LlmAdapter bind(LlmAdapter model, RetrievalFilter scope, long timeoutMs, Consumer<RagStreamEventMapper.Event> sink) {
        return bind(model, scope, timeoutMs, 5, sink);
    }

    private LlmAdapter bind(LlmAdapter model, RetrievalFilter scope, long timeoutMs, int modelLimit, Consumer<RagStreamEventMapper.Event> sink) {
        return request -> {
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            return subscriber -> {
                Execution execution = new Execution(model, request, scope, timeoutMs, modelLimit, sink, subscriber);
                if (!once.compareAndSet(false, true)) {
                    execution.cancel();
                    subscriber.onSubscribe(execution);
                    subscriber.onError(new IllegalStateException("one subscriber per knowledge request"));
                } else {
                    subscriber.onSubscribe(execution);
                }
            };
        };
    }

    private final class Execution implements Flow.Subscription, Runnable {
        private final LlmAdapter model;
        private final GenerateRequest base;
        private final RetrievalFilter scope;
        private final long timeoutMs;
        private final int modelLimit;
        private final Consumer<RagStreamEventMapper.Event> sink;
        private final Flow.Subscriber<? super StreamChunk> downstream;
        private volatile boolean cancelled;
        private volatile boolean terminal;
        private volatile Thread worker;
        private volatile Flow.Subscription upstream;
        private boolean started;
        private long demand;
        private long deadline;
        private Integer inputUsage = 0;
        private Integer outputUsage = 0;
        private int estimatedInputTotal;
        private int modelCalls;

        private Execution(LlmAdapter model, GenerateRequest base, RetrievalFilter scope, long timeoutMs, int modelLimit,
                           Consumer<RagStreamEventMapper.Event> sink, Flow.Subscriber<? super StreamChunk> downstream) {
            this.model = model;
            this.base = base;
            this.scope = scope;
            this.timeoutMs = timeoutMs;
            this.modelLimit = modelLimit;
            this.sink = sink;
            this.downstream = downstream;
        }

        @Override
        public synchronized void request(long n) {
            if (cancelled || terminal) {
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
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                try {
                    workers.execute(this);
                } catch (RejectedExecutionException e) {
                    terminal = true;
                    downstream.onError(new GroundedAnswerService.Failure("BUSY", "知识库问答容量已满，请稍后重试"));
                }
            }
        }

        @Override
        public void cancel() {
            synchronized (this) {
                cancelled = true;
                notifyAll();
            }
            var subscription = upstream;
            if (subscription != null) {
                subscription.cancel();
            }
            Thread value = worker;
            if (value != null && value != Thread.currentThread()) {
                value.interrupt();
            }
        }

        @Override
        public void run() {
            worker = Thread.currentThread();
            try {
                remaining();
                String question = base.messages().stream().filter(message -> message.role() == Message.Role.USER).reduce((a, b) -> b).orElseThrow().content();
                if (experiments.rewrite()) {
                    var rewritten = new QueryRewriter().rewrite(base);
                    question = rewritten.query();
                    // 只保存是否补全，不把历史问题正文额外写入事件或日志。
                    event("RAG_QUERY_REWRITE", Map.of("changed", rewritten.changed(), "kind", "PREVIOUS_USER_CONTEXT"));
                }
                event("RAG_EXPERIMENTS", Map.of("hybridRequested", experiments.hybrid(), "rerankRequested", experiments.rerank()));
                event("RAG_RETRIEVAL_STARTED", Map.of("knowledgeBaseIds", scope.knowledgeBaseIds()));
                var evidence = answers.retrieve(question, scope);
                remaining();
                event("RAG_EVIDENCE", Map.of("citations", evidence.citations()));
                if (evidence.citations().isEmpty()) {
                    refuse("NO_EVIDENCE");
                    return;
                }
                if (experiments.rerank() && evidence.citations().size() > 1 && modelLimit - modelCalls >= 3) {
                    boolean measured = false;
                    try {
                        var reranker = new Reranker();
                        var reranked = generate(reranker.request(question, evidence), false);
                        measured = true;
                        var ordered = reranker.apply(reranked.message().content(), evidence);
                        evidence = ordered;
                        event("RAG_RERANKED", Map.of("sourceIds", evidence.citations().stream().map(Citation::id).toList()));
                    } catch (CancellationException | InterruptedException error) { throw error; }
                    catch (Exception error) {
                        remaining();
                        if (upstream != null) upstream.cancel();
                        // 调用失败时总usage无法完整确认；不把已经消耗的失败请求伪装成零用量。
                        if (!measured) {
                            inputUsage = null;
                            outputUsage = null;
                        }
                        event("RAG_RERANK_FALLBACK", Map.of("reason", "INVALID_OR_FAILED_RERANK"));
                    }
                } else if (experiments.rerank()) {
                    event("RAG_RERANK_SKIPPED", Map.of("reason", "BUDGET_OR_SINGLE_CANDIDATE"));
                }
                event("RAG_JUDGE_STARTED", Map.of());
                var judged = generate(answers.decisionRequest(question, evidence), false);
                var decision = validator.decision(judged.message().content(), evidence);
                event("RAG_DECISION", Map.of("answerable", decision.answerable(), "sourceIds", decision.sourceIds()));
                if (!decision.answerable()) {
                    refuse("INSUFFICIENT_EVIDENCE");
                    return;
                }
                var selected = evidence.select(decision.sourceIds());
                answers.requireCurrent(scope.ownerId(), selected);
                event("RAG_ANSWER_STARTED", Map.of());
                var result = generate(answers.answerRequest(base, selected), true);
                var used = validator.validateAnswer(result.message().content(), selected);
                answers.requireCurrent(scope.ownerId(), selected.select(used));
                event("RAG_VALIDATED", Map.of("sourceIds", used));
                finish("STOP");
            } catch (Exception e) {
                if (!cancelled && !terminal) {
                    terminal = true;
                    downstream.onError(e instanceof GroundedAnswerService.Failure ? e
                            : new GroundedAnswerService.Failure("RAG_FAILED", "知识库处理失败，请检查模型、资料或Embedding配置"));
                }
            } finally {
                if (upstream != null) {
                    upstream.cancel();
                }
                worker = null;
            }
        }

        private StepResult generate(GenerateRequest request, boolean stream) throws Exception {
            remaining();
            if (modelCalls >= modelLimit) throw new GroundedAnswerService.Failure("BUDGET_EXCEEDED", "知识库模型调用预算已耗尽");
            estimatedInputTotal += request.messages().stream().mapToInt(new cn.ray.agent.runtime.context.TokenEstimator()::estimate).sum();
            event("RAG_CONTEXT_SELECTED", Map.of("estimatedInputTotal", estimatedInputTotal, "modelCalls", ++modelCalls));
            StepResult result = new StepResult();
            CompletableFuture<StepResult> completed = new CompletableFuture<>();
            model.generate(request).subscribe(new Flow.Subscriber<>() {
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
                public void onNext(StreamChunk value) {
                    if (cancelled || completed.isDone()) {
                        return;
                    }
                    try {
                        if (value instanceof StreamChunk.ToolCallDelta) {
                            throw new GroundedAnswerService.Failure("TOOL_FORBIDDEN", "知识库模式不允许工具调用");
                        }
                        result.accept(value);
                        if (stream && value instanceof StreamChunk.TextDelta) {
                            emit(value);
                        }
                    } catch (Exception e) {
                        completed.completeExceptionally(e);
                        upstream.cancel();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    completed.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    completed.complete(result);
                }
            });
            try {
                StepResult done = completed.get(remaining(), TimeUnit.MILLISECONDS);
                done.message();
                if (done.finished().reason() != StreamChunk.FinishReason.STOP) {
                    throw new GroundedAnswerService.Failure("MODEL_INCOMPLETE", "模型未完整返回知识库处理结果");
                }
                var usage = done.finished().usage();
                inputUsage = sum(inputUsage, usage == null ? null : usage.inputTokens());
                outputUsage = sum(outputUsage, usage == null ? null : usage.outputTokens());
                return done;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof GroundedAnswerService.Failure failure) {
                    throw failure;
                }
                throw e;
            } catch (TimeoutException e) {
                throw new GroundedAnswerService.Failure("TIMEOUT", "知识库问答超时");
            }
        }

        private Integer sum(Integer a, Integer b) {
            return a == null || b == null ? null : Math.addExact(a, b);
        }

        private void refuse(String code) throws InterruptedException {
            event("RAG_REFUSED", Map.of("reason", code));
            emit(new StreamChunk.TextDelta(GroundedAnswerService.REFUSAL));
            finish("INSUFFICIENT_EVIDENCE");
        }

        private void finish(String reason) throws InterruptedException {
            event("RAG_FINISHED", Map.of("reason", reason));
            emit(new StreamChunk.Finished(StreamChunk.FinishReason.STOP, new TokenUsage(inputUsage, outputUsage)));
            if (!cancelled) {
                terminal = true;
                downstream.onComplete();
            }
        }

        private void event(String type, Map<String, Object> data) {
            remaining();
            sink.accept(new RagStreamEventMapper.Event(type, data));
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

        private long remaining() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) {
                throw new GroundedAnswerService.Failure("TIMEOUT", "知识库问答超时");
            }
            return left;
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
