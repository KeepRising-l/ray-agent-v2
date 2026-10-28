package cn.ray.agent.runtime.model;

import java.time.Duration;
import java.util.Objects;

/** 仅在尚未向调用方输出事件时允许重试；包含首次请求在内的最大尝试次数。 */
public record RetryPolicy(int maxAttempts, Duration delay) {
    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("maxAttempts must be 1 to 5");
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isNegative() || delay.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("invalid retry delay");
    }

    /** 仅对连接失败、限流和服务端错误重试，不能重试已经向外输出的流。 */
    public boolean shouldRetry(int attempt, boolean emitted, boolean transientFailure) {
        return !emitted && transientFailure && attempt < maxAttempts;
    }
}
