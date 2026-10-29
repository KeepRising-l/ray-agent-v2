package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.model.GenerateRequest;
import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.runtime.model.Message;
import cn.ray.agent.runtime.model.ModelRegistry;
import cn.ray.agent.runtime.model.StreamChunk;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 连接聊天入口和模型适配器；本阶段每次请求独立，不保存历史。 */
@Service
public class ChatApplicationService {
    private final ModelRegistry registry;

    public ChatApplicationService(ModelRegistry registry) {
        this.registry = registry;
    }

    /** 创建 SSE 输出并订阅一次模型生成。 */
    public SseEmitter stream(String message) {
        LlmAdapter adapter;
        try {
            adapter = registry.getRequired("openai-compatible");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型尚未配置");
        }

        //用户输入包装模型请求
        GenerateRequest request = new GenerateRequest(List.of(new Message(Message.Role.USER, message)));
        SseEmitter emitter = new SseEmitter(60_000L);
        //创建模型事件的接收者
        ChatSubscriber subscriber = new ChatSubscriber(emitter);
        //注册结束、超时、错误时调用情况
        emitter.onCompletion(subscriber::cancel);
        emitter.onTimeout(subscriber::timeout);
        emitter.onError(error -> subscriber.cancel());
        //接上模型事件流
        adapter.generate(request).subscribe(subscriber);
        return emitter;
    }

    /** 把模型事件转为前端业务事件；断开或超时时向模型订阅传递取消。 */
    private static final class ChatSubscriber implements Flow.Subscriber<StreamChunk> {
        private final SseEmitter emitter;
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private StreamChunk.Finished finished;

        private ChatSubscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value) || terminal.get()) {
                value.cancel();
                return;
            }
            value.request(1);
        }

        @Override
        public void onNext(StreamChunk chunk) {
            if (terminal.get()) {
                return;
            }
            if (chunk instanceof StreamChunk.TextDelta text) {
                if (!send("text", Map.of("text", text.text()))) {
                    return;
                }
            } else if (chunk instanceof StreamChunk.Finished result) {
                finished = result;
            } else {
                fail("当前聊天暂不支持工具调用");
                return;
            }
            Flow.Subscription current = subscription.get();
            if (current != null && !terminal.get()) {
                current.request(1);
            }
        }

        @Override
        public void onError(Throwable error) {
            fail("模型调用失败，请检查配置或稍后重试");
        }

        @Override
        public void onComplete() {
            if (finished == null) {
                fail("模型流意外结束，请重试");
                return;
            }
            if (terminal.compareAndSet(false, true)) {
                if (send("done", Map.of("reason", finished.reason().name()))) {
                    emitter.complete();
                }
            }
        }

        /** 避免将上游错误细节或凭据直接返回浏览器。 */
        private void fail(String message) {
            if (terminal.compareAndSet(false, true)) {
                cancelSubscription();
                if (send("error", Map.of("message", message))) {
                    emitter.complete();
                }
            }
        }

        private boolean send(String name, Map<String, String> data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
                return true;
            } catch (IOException | IllegalStateException e) {
                terminal.set(true);
                cancelSubscription();
                return false;
            }
        }

        private void timeout() {
            fail("请求超时，请重试");
        }

        private void cancel() {
            terminal.set(true);
            cancelSubscription();
        }

        private void cancelSubscription() {
            Flow.Subscription current = subscription.get();
            if (current != null) {
                current.cancel();
            }
        }
    }
}
