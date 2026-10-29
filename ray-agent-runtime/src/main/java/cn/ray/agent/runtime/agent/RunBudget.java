package cn.ray.agent.runtime.agent;

/** 一次完整运行的上限；modelSteps 包含最后一次生成，工具次数按实际尝试累计。 */
public record RunBudget(int modelSteps, int toolCalls, long timeoutMs, long toolTimeoutMs, int inputTokens) {
    public RunBudget {
        if (modelSteps < 1 || modelSteps > 10 || toolCalls < 0 || toolCalls > 20
                || timeoutMs < 1 || toolTimeoutMs < 1 || inputTokens < 1024) {
            throw new IllegalArgumentException("invalid run budget");
        }
    }
}
