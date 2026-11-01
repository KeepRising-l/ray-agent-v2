package cn.ray.agent.server.rag.retrieve;

import java.util.List;

/** owner 由服务端提供；知识库来自已校验的会话范围，不能接受模型自行扩大范围。 */
public record RetrievalFilter(String ownerId, List<String> knowledgeBaseIds) {
    public RetrievalFilter {
        if (knowledgeBaseIds == null) throw new IllegalArgumentException("knowledge scope required");
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        if (ownerId == null || ownerId.isBlank() || knowledgeBaseIds.isEmpty() || knowledgeBaseIds.size() > 5) {
            throw new IllegalArgumentException("invalid retrieval scope");
        }
    }
}
