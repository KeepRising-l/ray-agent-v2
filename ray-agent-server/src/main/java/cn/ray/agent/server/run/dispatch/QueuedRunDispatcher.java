package cn.ray.agent.server.run.dispatch;

import cn.ray.agent.server.run.admission.BoundedRunExecutor;

import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.run.RunHandle;
import cn.ray.agent.server.run.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.Flow;

/** 一个订阅桥跨越排队和执行阶段：排队时累积需求，取消先于上游订阅也立即生效。 */
@Component
public class QueuedRunDispatcher implements RunDispatcher {
    private final BoundedRunExecutor executor;
    private final TaskStateRepository states;

    public QueuedRunDispatcher(BoundedRunExecutor executor, TaskStateRepository states) {
        this.executor = executor;
        this.states = states;
    }

    @Override
    public RunHandle dispatch(Context context, LlmAdapter adapter, GenerateRequest request,
                              Flow.Subscriber<StreamChunk> subscriber) {
        var handle = new RunHandle();
        var bridge = new Bridge(context, subscriber);
        handle.attach(bridge);
        subscriber.onSubscribe(handle);
        if (!context.permit().closed()) {
            executor.submit(context.permit(), context.sessionId(), context.runId(), context.queueSeq(), () -> {
                try {
                    if (context.permit().closed()) {
                        return;
                    }
                    if (!states.start(context.ownerId(), context.runId())) {
                        // 启动事务未成功时下游不会收到终态；这里释放闸门，避免会话被永久挡住。
                        executor.release(context.sessionId(), context.runId());
                        bridge.cancel();
                        return;
                    }
                    context.started().run();
                    if (!context.permit().closed()) {
                        adapter.generate(request).subscribe(bridge);
                    }
                } catch (Throwable error) {
                    bridge.onError(error);
                }
            }, () -> bridge.onError(new IllegalStateException("运行执行器拒绝任务，请稍后重试")));
        }
        return handle;
    }

    private static final class Bridge implements Flow.Subscriber<StreamChunk>, Flow.Subscription {
        private final Context context;
        private final Flow.Subscriber<StreamChunk> downstream;
        private Flow.Subscription upstream;
        // 排队时还没有上游订阅，先累计下游请求的事件数，取得槽后再转交。
        private long demand;
        private boolean terminal;

        private Bridge(Context context, Flow.Subscriber<StreamChunk> downstream) {
            this.context = context;
            this.downstream = downstream;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                onError(new IllegalArgumentException("positive demand required"));
                return;
            }
            Flow.Subscription value;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
                value = upstream;
            }
            if (value != null) {
                value.request(n);
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            long requested;
            synchronized (this) {
                if (terminal || upstream != null) {
                    value.cancel();
                    return;
                }
                upstream = value;
                requested = demand;
            }
            if (requested > 0) {
                value.request(requested);
            }
        }

        @Override
        public void onNext(StreamChunk value) {
            synchronized (this) {
                if (terminal) {
                    return;
                }
                if (demand != Long.MAX_VALUE) {
                    demand--;
                }
            }
            downstream.onNext(value);
        }

        @Override
        public void onError(Throwable error) {
            if (end()) {
                try {
                    downstream.onError(error);
                } finally {
                    context.permit().close();
                }
            }
        }

        @Override
        public void onComplete() {
            if (end()) {
                try {
                    downstream.onComplete();
                } finally {
                    context.permit().close();
                }
            }
        }

        private synchronized boolean end() {
            if (terminal) {
                return false;
            }
            terminal = true;
            return true;
        }

        @Override
        public void cancel() {
            Flow.Subscription value;
            synchronized (this) {
                terminal = true;
                value = upstream;
            }
            if (value != null) {
                value.cancel();
            }
            context.permit().close();
        }
    }
}
