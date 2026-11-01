package cn.ray.agent.server.rag.retrieve;

import java.util.*;

/** RRF只合并名次：每路贡献1/(60+名次)，不直接相加余弦相似度和关键词匹配分。 */
public final class RankFusion {
    public List<RetrievedChunk> fuse(List<RetrievedChunk> vector, List<RetrievedChunk> keyword, int limit) {
        if (limit < 1 || limit > 8) throw new IllegalArgumentException("invalid fusion limit");
        var chunks = new HashMap<String, RetrievedChunk>();
        var ranks = new HashMap<String, Double>();
        for (var channel : List.of(vector, keyword)) {
            var seen = new HashSet<String>();
            int rank = 0;
            for (var hit : channel) {
                if (!seen.add(hit.chunkId())) continue;
                chunks.putIfAbsent(hit.chunkId(), hit);
                ranks.merge(hit.chunkId(), 1.0 / (60 + ++rank), Double::sum);
            }
        }
        return chunks.values().stream().sorted(Comparator
                .<RetrievedChunk>comparingDouble(hit -> ranks.get(hit.chunkId())).reversed()
                .thenComparing(RetrievedChunk::chunkId)).limit(limit).toList();
    }
}
