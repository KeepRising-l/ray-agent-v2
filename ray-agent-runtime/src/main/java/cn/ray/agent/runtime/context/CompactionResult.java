package cn.ray.agent.runtime.context;

/** 即使摘要比原文更长也记录实际估算，只有确实缩短且格式有效时才发布新投影。 */
public record CompactionResult(String summary, int beforeTokens, int afterTokens, boolean accepted, String reason) { }
