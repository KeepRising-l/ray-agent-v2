package cn.ray.agent.runtime.context;

/** 输入预算是本地估算值；输出预算另行传给模型，不等于供应商实际 token 用量。 */
public record ContextBudget(int maxInputTokens, int maxOutputTokens) {
    public ContextBudget {
        if (maxInputTokens < 1 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("context budgets must be positive");
        }
    }
}
