package cn.ray.agent.runtime.run;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 保存一次运行的模型订阅；即使取消先于订阅建立，也会在订阅到达时立即取消。 */
public final class RunHandle implements Flow.Subscription {
    private final AtomicReference<Flow.Subscription> delegate = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void attach(Flow.Subscription subscription) {
        if (!delegate.compareAndSet(null, subscription) || cancelled.get()) {
            subscription.cancel();
        }
    }

    @Override
    public void request(long n) {
        Flow.Subscription value = delegate.get();
        if (value != null && !cancelled.get()) {
            value.request(n);
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        Flow.Subscription value = delegate.get();
        if (value != null) {
            value.cancel();
        }
    }
}
