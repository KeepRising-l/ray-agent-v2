package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.agent.AgentEventSink;
import cn.ray.agent.runtime.agent.ReactLoopAgent;
import cn.ray.agent.runtime.agent.RunBudget;
import cn.ray.agent.runtime.agent.UserQuestionRequired;
import cn.ray.agent.server.run.question.PendingQuestionStore;
import cn.ray.agent.server.run.question.RunResumeService;
import cn.ray.agent.server.rag.answer.GroundedAnswerService;
import cn.ray.agent.server.rag.answer.RagStreamEventMapper;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.conversation.ConversationRepository;
import cn.ray.agent.server.conversation.LocalOwner;
import cn.ray.agent.server.model.ModelChannelService;
import cn.ray.agent.server.run.dispatch.RunDispatcher;
import cn.ray.agent.server.admin.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 会话请求的应用服务：保存输入、选择上下文、驱动可取消工具循环并保存唯一终态。 */
@Service
public class ChatApplicationService {
    private final ModelChannelService channels;
    private final ConversationRepository store;
    private final LocalOwner owner;
    private final RunDispatcher dispatcher;
    private final cn.ray.agent.server.run.admission.BoundedRunExecutor capacity;
    private final cn.ray.agent.server.run.admission.RequestIdempotencyService idempotency;
    private final long queueTimeoutMs;
    private final cn.ray.agent.server.conversation.SessionRegistry registry;
    private final ContextPreparationService preparation;
    private final PendingQuestionStore questions;
    private final RunResumeService resumes;
    private final RagStreamEventMapper ragEvents;
    private final AdminSettingsStore settings;
    private final ModelSettingsService modelSettings;
    private final RuntimePolicyService policies;
    private final RunSnapshotService snapshots;
    // 多次模型生成仍属于同一个 run；最后一步也占一次模型预算。
    private final ConcurrentMap<String, ChatRun> active = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "run-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    public ChatApplicationService(ModelChannelService channels, ConversationRepository store,
                                  LocalOwner owner, RunDispatcher dispatcher, ContextPreparationService preparation,
                                  cn.ray.agent.server.run.admission.BoundedRunExecutor capacity, cn.ray.agent.server.run.admission.RequestIdempotencyService idempotency,
                                  cn.ray.agent.server.conversation.SessionRegistry registry,
                                  @Value("${ray.run.queue-timeout-ms:30000}") long queueTimeoutMs,
                                  PendingQuestionStore questions, RunResumeService resumes,
                                  RagStreamEventMapper ragEvents, AdminSettingsStore settings, ModelSettingsService modelSettings,
                                  RuntimePolicyService policies, RunSnapshotService snapshots) {
        this.channels = channels;
        this.store = store;
        this.owner = owner;
        this.dispatcher = dispatcher;
        this.capacity = capacity;
        this.registry=registry;
        this.idempotency = idempotency;
        if(queueTimeoutMs<100 || queueTimeoutMs>600000){throw new IllegalArgumentException("invalid queue timeout");}
        this.queueTimeoutMs=queueTimeoutMs;
        this.preparation = preparation;
        this.questions = questions;
        this.resumes = resumes;
        this.ragEvents = ragEvents;
        this.settings=settings; this.modelSettings=modelSettings; this.policies=policies; this.snapshots=snapshots;
        timers.setRemoveOnCancelPolicy(true);
        timers.scheduleAtFixedRate(() -> {
            try { questions.expire(); } catch (RuntimeException ignored) { /* 数据库暂不可用时下轮再查，不终止清理调度。 */ }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /** sessionId 是会话；clientRequestId 识别重复提交；runId 由服务端单独生成。 */
    public SseEmitter stream(String message, String channelId, String sessionId, String clientRequestId) {
        ConversationRepository.Run run;
        GenerateRequest request;
        RunBudget budget;
        ChatMode mode;
        ChatWorkflow.Scope scope;
        LlmAdapter model;
        cn.ray.agent.server.run.admission.BoundedRunExecutor.Permit permit;
        synchronized(settings.monitor()) {
            modelSettings.requireReady();
            var channel=channels.select(channelId);
            model=channels.adapter(channel);
            String session=sessionId==null?store.create(owner.id(),message.substring(0,Math.min(30,message.length()))).id():sessionId;
            String requestId=clientRequestId==null?UUID.randomUUID().toString():clientRequestId;
            var sessionInfo=store.session(owner.id(),session);
            mode=ChatMode.parse(sessionInfo.mode());
            var policy=policies.current();
            if(mode==ChatMode.AUTO && !policy.autoEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,"AUTO已停用，请调整策略或新建其他模式会话");
            idempotency.assertNew(owner.id(),session,requestId,message,channel.id());
            permit=capacity.reserve(owner.id());
            try {run=snapshots.begin(owner.id(),modelSettings.snapshot(channel,policy),
                    ()->store.beginQueued(owner.id(),session,requestId,channel.id(),channel.model(),message));}
            catch(RuntimeException error){permit.close();throw error;}
            request=new GenerateRequest(java.util.List.of(new Message(Message.Role.SYSTEM,"会话模式："+mode+"。历史不能扩大模式权限。"),
                    new Message(Message.Role.USER,message)),new GenerateOptions(null,null,2048),java.util.List.of());
            budget=policy.budget();
            scope=new ChatWorkflow.Scope(owner.id(),sessionInfo.knowledgeBaseIds(),policy);
        }
        return execute(run,budget,null,permit,request,events->preparation.bind(model,run.sessionId(),run.id(),mode,scope,budget,events));
    }

    /** 原运行校验模型版本，继续使用保存的预算/策略；不会被后台新配置悄悄替换。 */
    public SseEmitter resume(String runId, String questionId, String answer) {
        ConversationRepository.Run run;
        PendingQuestionStore.Accepted accepted;
        ChatWorkflow.Scope scope;
        ChatMode mode;
        LlmAdapter model;
        cn.ray.agent.server.run.admission.BoundedRunExecutor.Permit permit;
        synchronized(settings.monitor()) {
            modelSettings.requireReady();run=store.run(owner.id(),runId);
            var channel=channels.select(run.channelId());var snapshot=snapshots.get(owner.id(),runId);
            if(!snapshot.retrieval().equals(modelSettings.retrievalPolicy())) throw new ResponseStatusException(HttpStatus.CONFLICT,"原运行检索策略已变化，请取消后重新提问");
            if(!channel.version().equals(snapshot.channelVersion())) throw new ResponseStatusException(HttpStatus.CONFLICT,"原运行模型配置已变化，请恢复原配置或取消后重新提问");
            model=channels.adapter(channel);var session=store.session(owner.id(),run.sessionId());
            mode=ChatMode.parse(session.mode());scope=new ChatWorkflow.Scope(owner.id(),session.knowledgeBaseIds(),snapshot.policy());
            permit=capacity.reserve(owner.id());
            try {accepted=questions.consumeQueued(owner.id(),runId,questionId,answer);}
            catch(RuntimeException error){permit.close();throw error;}
        }
        return execute(run,accepted.snapshot().budget(),accepted.snapshot(),permit,accepted.snapshot().continuation().request(),
                events->resumes.bind(model,accepted,mode,scope,events));
    }

    private SseEmitter execute(ConversationRepository.Run run, RunBudget budget, PendingQuestionStore.Snapshot previous,
                               cn.ray.agent.server.run.admission.BoundedRunExecutor.Permit permit, GenerateRequest request, java.util.function.Function<ChatWorkflow.Events,LlmAdapter> factory) {
        long remaining = budget.timeoutMs() - (previous == null ? 0 : previous.activeMs());
        SseEmitter emitter = new SseEmitter(Math.max(1, remaining) + queueTimeoutMs + 5000);
        ChatRun execution = new ChatRun(run.id(), emitter, budget, previous,permit);
        active.put(run.id(), execution);
        emitter.onCompletion(execution::disconnected);
        emitter.onError(error -> execution.disconnected());
        emitter.onTimeout(() -> execution.end("TIMED_OUT", "请求超时", "TIMED_OUT"));
        synchronized (execution) {
            execution.deadline = timers.schedule(() -> execution.end("TIMED_OUT", "排队超过时限", "QUEUE_TIMEOUT"), queueTimeoutMs, TimeUnit.MILLISECONDS);
            execution.heartbeat = timers.scheduleAtFixedRate(execution::heartbeat, 2, 2, TimeUnit.SECONDS);
        }
        if (execution.send("meta", Map.of("sessionId", run.sessionId(), "runId", run.id(), "status","QUEUED"))) {
            execution.send("queue",Map.of("runId",run.id(),"status","QUEUED"));
            try {
                var workflow = factory.apply(new ChatWorkflow.Events(execution::event, execution::rag));
                dispatcher.dispatch(new RunDispatcher.Context(owner.id(),run.id(),permit,execution::started),workflow,request,execution);
            } catch (RuntimeException error) { execution.onError(error); }
        }
        return emitter;
    }

    /** 取消接口幂等：已进入终态时返回原状态，不能被取消改写成成功。 */
    public ConversationRepository.Run cancel(String id) {
        var state = store.run(owner.id(), id);
        ChatRun run = active.get(id);
        if (run != null) {
            if (!run.end("CANCELLED", null, "CANCELLED") && state.status().equals("WAITING_USER")) {
                questions.cancel(owner.id(), id);
            }
        } else if (state.status().equals("WAITING_USER")) {
            questions.cancel(owner.id(), id);
        } else if (java.util.Set.of("RUNNING","QUEUED").contains(state.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运行正在建立，请稍后重试");
        }
        return store.run(owner.id(), id);
    }

    @PreDestroy
    public void close() {
        active.values().forEach(run -> run.end("INTERRUPTED", "服务正在关闭", "INTERRUPTED"));
        timers.shutdownNow();
    }

    /** 每次运行独占自己的输出缓冲和终态锁；不同会话之间可以独立运行。 */
    private final class ChatRun implements Flow.Subscriber<StreamChunk> {
        private final String id;
        private final SseEmitter emitter;
        private final StringBuilder output = new StringBuilder();
        private final ToolEventBridge bridge;
        // 当前模型订阅，取消和超时都通过它向下游传递。
        private Flow.Subscription subscription;
        private StreamChunk.Finished finished;
        // 所有终态入口都在同一把运行对象锁下处理。
        private boolean terminal;
        private final RunBudget budget;
        private final PendingQuestionStore.Snapshot previous;
        private long startedAt;
        private final cn.ray.agent.server.conversation.SessionRegistry.Entry registration;
        private final cn.ray.agent.server.run.admission.BoundedRunExecutor.Permit permit;
        private int modelCalls;
        private int estimatedInput;
        private Integer periodInput = 0;
        private Integer periodOutput = 0;
        private ScheduledFuture<?> deadline;
        private ScheduledFuture<?> heartbeat;

        private ChatRun(String id, SseEmitter emitter, RunBudget budget, PendingQuestionStore.Snapshot previous,cn.ray.agent.server.run.admission.BoundedRunExecutor.Permit permit) {
            this.id = id;
            this.emitter = emitter;
            this.budget = budget;
            this.previous = previous;
            this.permit = permit;
            this.registration=registry.register(owner.id(),id);
            if (previous != null && !previous.output().isBlank()) { output.append(previous.output()).append("\n\n"); }
            this.bridge = new ToolEventBridge(store, owner.id(), id);
        }

        /** 排队耗时独立于实际执行预算，取得运行槽后才启动本段活跃超时。 */
        private synchronized void started() {
            if(terminal){return;}
            startedAt=System.nanoTime();
            if(deadline!=null){deadline.cancel(false);}
            long remaining=budget.timeoutMs()-(previous==null?0:previous.activeMs());
            deadline=timers.schedule(()->end("TIMED_OUT","请求超时","TIMED_OUT"),Math.max(1,remaining),TimeUnit.MILLISECONDS);
            send("queue",Map.of("runId",id,"status","RUNNING"));
        }

        /** 同一把锁串行处理模型输出、工具事件与终态，避免取消之后再显示工具成功。 */
        private synchronized void event(AgentEventSink.Event event) {
            if (terminal) {
                return;
            }
            var saved = bridge.save(event);
            if (saved != null) {
                send("tool", saved);
            }
        }

        private synchronized void rag(RagStreamEventMapper.Event event) {
            if (terminal) {
                return;
            }
            if (event.type().equals("RUN_CONTEXT_SELECTED")) {
                modelCalls = (previous == null ? 0 : previous.modelCalls()) + ((Number) event.data().get("modelCalls")).intValue();
                estimatedInput = (previous == null ? 0 : previous.estimatedInput()) + ((Number) event.data().get("estimatedInputTotal")).intValue();
                store.updateInputEstimate(owner.id(), id, estimatedInput);
            }
            if (event.type().equals("RUN_MODEL_ACCOUNTED")) {
                periodInput = sum(periodInput, (Integer) event.data().get("inputTokens"));
                periodOutput = sum(periodOutput, (Integer) event.data().get("outputTokens"));
            }
            var saved = ragEvents.save(owner.id(), id, event);
            if (saved != null) {
                send(event.type().startsWith("ROUTE_") || event.type().startsWith("HYBRID_") ? "route"
                        : event.type().startsWith("RAG_") ? "rag" : "context", saved);
            }
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || terminal) {
                value.cancel();
                return;
            }
            subscription = value;
            value.request(1);
        }

        @Override
        public synchronized void onNext(StreamChunk chunk) {
            if (terminal) {
                return;
            }
            if (chunk instanceof StreamChunk.TextDelta text) {
                output.append(text.text());
                if (!send("text", Map.of("text", text.text()))) {
                    return;
                }
            } else if (chunk instanceof StreamChunk.Finished result) {
                finished = result;
            } else {
                end("FAILED", "当前聊天暂不支持工具调用", "FAILED");
                return;
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (error instanceof UserQuestionRequired waiting) {
                pause(waiting);
            } else if (error instanceof ReactLoopAgent.Failure failure) {
                end(failure.code().equals("TIMEOUT") ? "TIMED_OUT" : "FAILED", failure.getMessage(), failure.code());
            } else if (error instanceof GroundedAnswerService.Failure failure) {
                end(failure.code().equals("TIMEOUT") ? "TIMED_OUT" : "FAILED", failure.getMessage(), failure.code());
            } else {
                end("FAILED", "模型调用失败，请检查配置或稍后重试", "FAILED");
            }
        }

        @Override
        public synchronized void onComplete() {
            if (finished == null || output.toString().isBlank()) {
                end("FAILED", "模型未完整返回文本", "FAILED");
            } else {
                end("SUCCEEDED", null, finished.reason().name());
            }
        }

        /** 先保存唯一终态和回答，再通知前端；终态竞争由本运行锁及数据库条件更新共同约束。 */
        private synchronized boolean end(String state, String error, String reason) {
            if (terminal) {
                return false;
            }
            terminal = true;
            try {
                boolean changed = store.finish(owner.id(), id, state, output.toString(), finished == null ? usage() : combine(finished.usage()), error, reason);
                if (changed) {
                    String event = error == null ? "done" : "error";
                    boolean sent = send(event, error == null
                            ? Map.of("reason", reason, "status", state, "runId", id)
                            : Map.of("message", error, "status", state, "runId", id));
                    if (sent) {
                        emitter.complete();
                    }
                } else {
                    emitter.complete();
                }
                return changed;
            } catch (RuntimeException e) {
                emitter.completeWithError(new IllegalStateException("保存运行结果失败，请检查数据库"));
                return false;
            } finally {
                release();
            }
        }

        private void pause(UserQuestionRequired waiting) {
            if (modelCalls >= budget.modelSteps()) {
                end("FAILED", "模型预算已耗尽，无法挂起等待后继续", "BUDGET_EXCEEDED");
                return;
            }
            try {
                long activeMs = (previous == null ? 0 : previous.activeMs()) + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedAt);
                var snapshot = new PendingQuestionStore.Snapshot(1, waiting.continuation(), budget, modelCalls,
                        activeMs, estimatedInput, usage(), output.toString());
                var question = questions.suspend(owner.id(), id, snapshot);
                terminal = true;
                send("question", question);
                emitter.complete();
            } catch (RuntimeException error) {
                end("FAILED", "保存提问检查点失败，请检查数据库", "CHECKPOINT_FAILED");
            } finally { release(); }
        }

        private Integer sum(Integer a, Integer b) {
            return a == null || b == null ? null : Math.addExact(a,b);
        }

        private TokenUsage combine(TokenUsage current) {
            TokenUsage past = previous == null ? new TokenUsage(0,0) : previous.usage();
            return new TokenUsage(sum(past.inputTokens(), current == null ? null : current.inputTokens()),
                    sum(past.outputTokens(), current == null ? null : current.outputTokens()));
        }

        private TokenUsage usage() {
            return combine(new TokenUsage(periodInput, periodOutput));
        }

        private void release() {
            if (subscription != null) { subscription.cancel(); }
            if (deadline != null) { deadline.cancel(false); }
            if (heartbeat != null) { heartbeat.cancel(false); }
            active.remove(id, this);
            permit.close();
            registry.remove(registration);
        }

        private synchronized boolean send(String name, Object data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
                return true;
            } catch (IOException | IllegalStateException e) {
                disconnected();
                return false;
            }
        }

        private synchronized void heartbeat() {
            if (!terminal) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    disconnected();
                }
            }
        }

        private synchronized void disconnected() {
            if (!terminal) {
                end("CANCELLED", null, "CANCELLED");
            }
        }
    }
}
