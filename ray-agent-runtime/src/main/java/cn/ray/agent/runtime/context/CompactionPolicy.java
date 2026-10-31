package cn.ray.agent.runtime.context;

/** 本地估算阈值不是供应商真实token数；摘要额外调用有输出、输入和保留轮次上限。 */
public record CompactionPolicy(int triggerTokens, int keepRecentTurns, int maxSourceTokens, int maxSummaryCharacters) {
    public CompactionPolicy {
        if (triggerTokens < 512 || keepRecentTurns < 1 || maxSourceTokens < 512 || maxSummaryCharacters < 100) {
            throw new IllegalArgumentException("invalid compaction policy");
        }
    }
}
