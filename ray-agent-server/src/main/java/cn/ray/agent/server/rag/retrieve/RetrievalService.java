package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.server.knowledge.DocumentRepository;
import cn.ray.agent.server.rag.embedding.EmbeddingClient;
import cn.ray.agent.server.rag.store.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;

/** 先校验身份范围，再调用查询 Embedding；小数据采用精确余弦排序。 */
@Service
public class RetrievalService {
    private final DocumentRepository documents;
    private final EmbeddingClient embedding;
    private final PgVectorStore vectors;
    private final Double minimumScore;

    public RetrievalService(DocumentRepository documents, EmbeddingClient embedding, PgVectorStore vectors,
                             @Value("${ray.rag.minimum-score:}") String score) {
        this.documents = documents;
        this.embedding = embedding;
        this.vectors = vectors;
        this.minimumScore = score.isBlank() ? null : Double.valueOf(score);
        if (minimumScore != null && (!Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1)) {
            throw new IllegalArgumentException("invalid minimum retrieval score");
        }
    }

    public List<RetrievedChunk> retrieve(String question, RetrievalFilter scope) {
        return retrieve(new RetrievalQuery(question, scope, 6, minimumScore));
    }

    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        for (String kb : query.filter().knowledgeBaseIds()) {
            documents.base(query.filter().ownerId(), kb);
        }
        if (!vectors.hasEligible(query.filter(), embedding.profile())) {
            return List.of();
        }
        float[] vector = embedding.embed(List.of(query.question())).get(0);
        return vectors.search(query, embedding.profile(), vector);
    }
}
