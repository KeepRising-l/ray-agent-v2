package cn.ray.agent.server.rag.store;

import cn.ray.agent.server.rag.chunk.DocumentChunk;
import cn.ray.agent.server.rag.embedding.EmbeddingProfile;
import java.util.List;

/** R04 只建立可核对的写入/读回边界，R05 再实现带权限过滤的检索。 */
public interface VectorStore {
    void write(EmbeddingProfile profile, List<DocumentChunk> chunks, List<float[]> vectors);

    int count(String versionId);
}
