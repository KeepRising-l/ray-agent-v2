package cn.ray.agent.runtime.model;

/** 供应商报告的 token 用量；单项为 null 表示未知，而不是零。 */
public record TokenUsage(Integer inputTokens, Integer outputTokens) {
    public TokenUsage {
        if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
