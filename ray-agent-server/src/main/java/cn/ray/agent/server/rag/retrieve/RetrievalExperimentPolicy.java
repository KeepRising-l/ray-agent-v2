package cn.ray.agent.server.rag.retrieve;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 优化默认关闭；启动时固定，写入run快照，不能把一次小样本收益当成默认策略依据。 */
@Component
public record RetrievalExperimentPolicy(boolean hybrid, boolean rewrite, boolean rerank) {
    public RetrievalExperimentPolicy(
            @Value("${ray.rag.experimental.hybrid:false}") boolean hybrid,
            @Value("${ray.rag.experimental.rewrite:false}") boolean rewrite,
            @Value("${ray.rag.experimental.rerank:false}") boolean rerank) {
        this.hybrid = hybrid;
        this.rewrite = rewrite;
        this.rerank = rerank;
    }
    public static RetrievalExperimentPolicy baseline() { return new RetrievalExperimentPolicy(false, false, false); }
}
