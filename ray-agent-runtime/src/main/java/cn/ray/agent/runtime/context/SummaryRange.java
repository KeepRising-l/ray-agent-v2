package cn.ray.agent.runtime.context;

import java.util.List;

/** 摘要覆盖的是成功运行的连续前缀，使用稳定runId和内容指纹，不能按可变的消息条数猜范围。 */
public record SummaryRange(List<String> runIds, String sourceHash) {
    public SummaryRange {
        runIds = List.copyOf(runIds);
        if (runIds.isEmpty() || runIds.stream().distinct().count() != runIds.size() || sourceHash == null || sourceHash.isBlank()) {
            throw new IllegalArgumentException("invalid summary range");
        }
    }
}
