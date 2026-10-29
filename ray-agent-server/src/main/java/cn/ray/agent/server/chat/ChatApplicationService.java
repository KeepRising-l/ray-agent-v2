package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.context.ContextAssembler;
import cn.ray.agent.runtime.context.ContextBudget;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.conversation.ConversationRepository;
import cn.ray.agent.server.conversation.LocalOwner;
import cn.ray.agent.server.model.ModelChannelService;
import cn.ray.agent.server.run.RunDispatcher;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 会话请求的应用服务：持久化输入、选择上下文、执行一次可取消运行并保存终态。 */
@Service
public class ChatApplicationService {
    private final ModelChannelService channels;
    private final ConversationRepository store;
    private final LocalOwner owner;
    private final RunDispatcher dispatcher;
    private final long timeoutMs;
    private final ContextAssembler context = new ContextAssembler();
    private final ConcurrentMap<String, ChatRun> active = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "run-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    public ChatApplicationService(ModelChannelService channels, ConversationRepository store,
                                  LocalOwner owner, RunDispatcher dispatcher,
                                  @Value("${ray.run.timeout-ms:60000}") long timeoutMs) {
        if (timeoutMs < 100 || timeoutMs > 600_000) {
            throw new IllegalArgumentException("run timeout must be between 100 and 600000 ms");
        }
        this.channels = channels;
        this.store = store;
        this.owner = owner;
        this.dispatcher = dispatcher;
        this.timeoutMs = timeoutMs;
        timers.setRemoveOnCancelPolicy(true);
    }

    /** sessionId 是会话；clientRequestId 识别重复提交；runId 由服务端单独生成。 */
    public SseEmitter stream(String message, String channelId, String sessionId, String clientRequestId) {
        var channel = channels.select(channelId);
        String session = sessionId == null ? store.create(owner.id(), message.substring(0, Math.min(30, message.length()))).id() : sessionId;
        String requestId = clientRequestId == null ? UUID.randomUUID().toString() : clientRequestId;
        var run = store.begin(owner.id(), session, requestId, channel.id(), channel.model(), message);
        GenerateRequest request;
        try {
            var messages = new ArrayList<Message>();
            messages.add(new Message(Message.Role.SYSTEM, "请准确回答用户问题；没有依据时明确说明。"));
            messages.addAll(store.history(owner.id(), session));
            messages.add(new Message(Message.Role.USER, message));
            var budget = new ContextBudget(16000, 1024);
            var selected = context.assemble(messages, budget);
            store.context(owner.id(), run.id(), selected.estimatedInputTokens(), selected.omittedTurns());
            request = new GenerateRequest(selected.messages(), new GenerateOptions(null, null, budget.maxOutputTokens()), java.util.List.of());
        } catch (RuntimeException e) {
            store.finish(owner.id(), run.id(), "FAILED", "", null, "上下文预算不足或输入不完整");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上下文预算不足或输入不完整");
        }

        SseEmitter emitter = new SseEmitter(timeoutMs + 5000);
        ChatRun execution = new ChatRun(run.id(), emitter);
        active.put(run.id(), execution);
        emitter.onCompletion(execution::disconnected);
        emitter.onError(error -> execution.disconnected());
        emitter.onTimeout(() -> execution.end("TIMED_OUT", "请求超时", "TIMED_OUT"));
        synchronized (execution) {
            execution.deadline = timers.schedule(() -> execution.end("TIMED_OUT", "请求超时", "TIMED_OUT"), timeoutMs, TimeUnit.MILLISECONDS);
            execution.heartbeat = timers.scheduleAtFixedRate(execution::heartbeat, 2, 2, TimeUnit.SECONDS);
        }
        if (execution.send("meta", Map.of("sessionId", session, "runId", run.id()))) {
            dispatcher.dispatch(channels.adapter(channel), request, execution);
        }
        return emitter;
    }

    /** 取消接口幂等：已进入终态时返回原状态，不能被取消改写成成功。 */
    public ConversationRepository.Run cancel(String id) {
        var state = store.run(owner.id(), id);
        ChatRun run = active.get(id);
        if (run != null) {
            run.end("CANCELLED", null, "CANCELLED");
        } else if (state.status().equals("RUNNING")) {
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
        // 当前模型订阅，取消和超时都通过它向下游传递。
        private Flow.Subscription subscription;
        private StreamChunk.Finished finished;
        // 所有终态入口都在同一把运行对象锁下处理。
        private boolean terminal;
        private ScheduledFuture<?> deadline;
        private ScheduledFuture<?> heartbeat;

        private ChatRun(String id, SseEmitter emitter) {
            this.id = id;
            this.emitter = emitter;
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
            end("FAILED", "模型调用失败，请检查配置或稍后重试", "FAILED");
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
                boolean changed = store.finish(owner.id(), id, state, output.toString(), finished == null ? null : finished.usage(), error, reason);
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
                if (subscription != null) {
                    subscription.cancel();
                }
                if (deadline != null) {
                    deadline.cancel(false);
                }
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                active.remove(id, this);
            }
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
