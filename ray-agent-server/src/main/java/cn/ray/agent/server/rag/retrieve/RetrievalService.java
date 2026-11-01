package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.server.knowledge.DocumentRepository;
import cn.ray.agent.server.rag.embedding.EmbeddingClient;
import cn.ray.agent.server.rag.store.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

/** 先校验身份范围，再调用查询 Embedding；小数据采用精确余弦排序。 */
@Service
public class RetrievalService {
    private final DocumentRepository documents;
    private final EmbeddingClient embedding;
    private final PgVectorStore vectors;
    private final Double minimumScore;
    private final HybridRetriever hybrid;
    private final RetrievalExperimentPolicy policy;

    // 保留原构造器供基线单测使用；生产装配必须明确注入实验策略。
    public RetrievalService(DocumentRepository documents, EmbeddingClient embedding, PgVectorStore vectors,
                             String score) {
        this(documents, embedding, vectors, score, null, RetrievalExperimentPolicy.baseline());
    }

    @Autowired
    public RetrievalService(DocumentRepository documents, EmbeddingClient embedding, PgVectorStore vectors,
                             @Value("${ray.rag.minimum-score:}") String score,
                             HybridRetriever hybrid, RetrievalExperimentPolicy policy) {
        this.documents = documents;
        this.embedding = embedding;
        this.vectors = vectors;
        this.hybrid = hybrid;
        this.policy = policy;
        this.minimumScore = score.isBlank() ? null : Double.valueOf(score);
        if (minimumScore != null && (!Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1)) {
            throw new IllegalArgumentException("invalid minimum retrieval score");
        }
    }

    public List<RetrievedChunk> retrieve(String question, RetrievalFilter scope) {
        return retrieve(new RetrievalQuery(question, scope, policy.rerank() ? 8 : 6, minimumScore));
    }

    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        for (String kb : query.filter().knowledgeBaseIds()) {
            documents.base(query.filter().ownerId(), kb);
        }
        if (!vectors.hasEligible(query.filter(), embedding.profile())) {
            return List.of();
        }
        float[] vector = embedding.embed(List.of(query.question())).get(0);
        if (policy.hybrid() && hybrid != null) {
            try { return hybrid.compare(query, embedding.profile(), vector).hybrid(); }
            catch (java.util.concurrent.CancellationException error) { throw error; }
            catch (RuntimeException error) {
                if (Thread.currentThread().isInterrupted()) throw error;
                org.slf4j.LoggerFactory.getLogger(RetrievalService.class).warn("RAG_KEYWORD_FALLBACK: using scoped vector baseline");
                // 关键词查询失败只回退同一次Embedding的向量基线；不会回退到未授权资料或网页。
                return vectors.search(query, embedding.profile(), vector);
            }
        }
        return vectors.search(query, embedding.profile(), vector);
    }

    /** 管理员离线对照入口：同一向量、同一范围和同一limit测三种排序，不修改线上策略。 */
    public HybridRetriever.Comparison compare(RetrievalQuery query) {
        for (String kb : query.filter().knowledgeBaseIds()) documents.base(query.filter().ownerId(), kb);
        if (!vectors.hasEligible(query.filter(), embedding.profile())) {
            return new HybridRetriever.Comparison(List.of(), List.of(), List.of(), 0, 0, 0);
        }
        float[] vector = embedding.embed(List.of(query.question())).get(0);
        return hybrid.compare(query, embedding.profile(), vector);
    }
}
