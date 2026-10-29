package cn.ray.agent.server.rag.chunk;

/** offset 为规范化段落内的 UTF-16 位置；id 由版本、策略和片段位置生成，重试可复现。 */
public record DocumentChunk(String id, String versionId, int ordinal, String content, Integer page,
                            int paragraph, String heading, int startOffset, int endOffset) { }
