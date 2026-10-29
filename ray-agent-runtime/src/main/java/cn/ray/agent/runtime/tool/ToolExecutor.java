package cn.ray.agent.runtime.tool;

import cn.ray.agent.runtime.model.ToolCall;
import java.util.Set;
import java.util.concurrent.*;

/** 权限、参数、超时和异常统一收口；异常正文不回传，避免泄漏密钥或网络内部信息。 */
public final class ToolExecutor implements AutoCloseable {
    private final ToolRegistry registry;
    private final ToolArgumentsParser parser = new ToolArgumentsParser();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "tool-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResult execute(ToolCall call, Set<String> allowed, ToolContext run, long timeoutMs) {
        run.checkCancelled();
        if (!allowed.contains(call.name())) {
            return ToolResult.failure("FORBIDDEN", "本次运行不允许调用该工具");
        }
        var entry = registry.find(call.name());
        if (entry == null) {
            return ToolResult.failure("UNKNOWN_TOOL", "工具未注册");
        }
        final com.fasterxml.jackson.databind.JsonNode arguments;
        try {
            arguments = parser.parse(call.argumentsJson(), entry.schema());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("INVALID_ARGUMENTS", "工具参数格式、字段或长度不符合定义");
        }
        ToolContext execution = new ToolContext();
        Future<ToolResult> future;
        try {
            future = workers.submit(() -> entry.tool().execute(arguments, execution));
        } catch (RejectedExecutionException e) {
            return ToolResult.failure("BUSY", "工具执行容量已满");
        }
        try (var ignored = run.onCancel(() -> {
            execution.cancel();
            future.cancel(true);
        })) {
            return java.util.Objects.requireNonNull(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return ToolResult.failure("TIMEOUT", "工具执行超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("run interrupted");
        } catch (CancellationException e) {
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof java.net.SocketTimeoutException
                    || e.getCause() instanceof java.net.http.HttpTimeoutException) {
                return ToolResult.failure("TIMEOUT", "工具网络请求超时");
            }
            return ToolResult.failure("EXECUTION_FAILED", "工具执行失败，请根据已有信息说明限制");
        } catch (Exception e) {
            return ToolResult.failure("EXECUTION_FAILED", "工具执行失败，请根据已有信息说明限制");
        } finally {
            execution.cancel();
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
