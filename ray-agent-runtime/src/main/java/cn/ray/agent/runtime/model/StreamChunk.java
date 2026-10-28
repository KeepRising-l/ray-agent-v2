package cn.ray.agent.runtime.model;

import java.util.Objects;

/** 模型流中的业务事件；失败通过 Publisher 的 onError 通知，不伪装成正常结束。 */
public sealed interface StreamChunk permits StreamChunk.TextDelta, StreamChunk.ToolCallDelta, StreamChunk.Finished {
    /** 一段新增的回答文本，并非完整回答。 */
    record TextDelta(String text) implements StreamChunk {
        public TextDelta { Objects.requireNonNull(text, "text must not be null"); }
    }

    /**
     * 一个工具调用的增量片段。同一 index 的片段需要按顺序组装；字段可能暂时缺失。
     * @param index 本次回答中工具调用的序号
     * @param id 调用 ID（可能仅在部分片段提供）
     * @param name 工具名（可能仅在部分片段提供）
     * @param argumentsFragment 参数 JSON 的片段，不保证单个片段是完整 JSON
     */
    record ToolCallDelta(int index, String id, String name, String argumentsFragment) implements StreamChunk {
        public ToolCallDelta {
            if (index < 0) throw new IllegalArgumentException("index must not be negative");
            if (id == null && name == null && argumentsFragment == null) {
                throw new IllegalArgumentException("tool call delta must contain data");
            }
        }
    }

    /** 模型一次生成的结束原因；usage 为 null 表示供应商没有提供用量。 */
    record Finished(FinishReason reason, TokenUsage usage) implements StreamChunk {
        public Finished { Objects.requireNonNull(reason, "reason must not be null"); }
    }

    enum FinishReason { STOP, TOOL_CALLS, LENGTH, OTHER }
}
