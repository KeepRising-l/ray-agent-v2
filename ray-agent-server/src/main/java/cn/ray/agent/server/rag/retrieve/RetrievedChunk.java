package cn.ray.agent.server.rag.retrieve;

/** 分数只是向量相似度，不能直接当成答案正确概率。 */
public record RetrievedChunk(String chunkId, String kbId, String documentId, String documentName,
                             String versionId, int version, String content, Integer page, int paragraph,
                             String heading, int startOffset, int endOffset, double score) { }
