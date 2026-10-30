package cn.ray.agent.server.rag.answer;

/** 本轮引用的定位元数据；不把召回全文永久追加进会话或事件。原文点开时重新校验。 */
public record Citation(String id, String kbId, String documentId, String documentName, String versionId,
                       int version, String chunkId, Integer page, int paragraph, String heading,
                       int startOffset, int endOffset, double score) { }
