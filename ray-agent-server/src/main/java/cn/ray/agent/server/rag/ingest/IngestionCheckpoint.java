package cn.ray.agent.server.rag.ingest;

import cn.ray.agent.server.rag.parse.DocumentParser;

/** 持久版本中记录的处理约束；重启/重试时不能悄悄换解析、分块或重建目标模型。 */
public record IngestionCheckpoint(String parserVersion, String chunkPolicy, String targetProfile) {
    public void verify(String parser, String policy, String profile) {
        if (!parserVersion.equals(parser) || !chunkPolicy.equals(policy)
                || (targetProfile != null && !targetProfile.equals(profile))) {
            throw new DocumentParser.Failure("处理或向量配置已变化，请重新重建索引");
        }
    }
}
