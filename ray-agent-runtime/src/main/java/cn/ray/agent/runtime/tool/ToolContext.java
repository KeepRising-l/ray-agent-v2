package cn.ray.agent.runtime.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** 一次执行的取消信号；关闭 HTTP 请求等资源的回调也登记在这里。 */
public final class ToolContext {
    private boolean cancelled;
    private final List<Runnable> hooks = new ArrayList<>();

    public synchronized void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("run cancelled");
        }
    }

    public synchronized AutoCloseable onCancel(Runnable hook) {
        if (cancelled) {
            hook.run();
        } else {
            hooks.add(hook);
        }
        return () -> {
            synchronized (this) {
                hooks.remove(hook);
            }
        };
    }

    public void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pending = List.copyOf(hooks);
            hooks.clear();
        }
        for (Runnable hook : pending) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // 一个资源关闭失败不能阻止其余资源取消。
            }
        }
    }
}
