package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.model.*;
import java.util.*;

/** 按 index 组装一次模型生成的碎片；只有完整结束后才允许执行工具。 */
public final class StepResult {
    private final StringBuilder text = new StringBuilder();
    private final SortedMap<Integer, Parts> calls = new TreeMap<>();
    private StreamChunk.Finished finished;

    public void accept(StreamChunk chunk) {
        if (finished != null) {
            throw new IllegalStateException("event after model finish");
        }
        if (chunk instanceof StreamChunk.TextDelta delta) {
            append(text, delta.text(), 64000);
        } else if (chunk instanceof StreamChunk.ToolCallDelta delta) {
            if (delta.index() > 7) {
                throw new IllegalStateException("too many parallel tool calls");
            }
            Parts part = calls.computeIfAbsent(delta.index(), key -> new Parts());
            append(part.id, delta.id(), 128);
            append(part.name, delta.name(), 128);
            append(part.arguments, delta.argumentsFragment(), 8192);
        } else if (chunk instanceof StreamChunk.Finished value) {
            finished = value;
        }
    }

    public Message message() {
        if (finished == null) {
            throw new IllegalStateException("model stream incomplete");
        }
        if ((finished.reason() == StreamChunk.FinishReason.TOOL_CALLS) != !calls.isEmpty()) {
            throw new IllegalStateException("tool finish reason mismatch");
        }
        Set<String> ids = new HashSet<>();
        List<ToolCall> assembled = new ArrayList<>();
        for (Parts parts : calls.values()) {
            ToolCall call = new ToolCall(parts.id.toString(), parts.name.toString(), parts.arguments.toString());
            if (!ids.add(call.id())) {
                throw new IllegalStateException("duplicate tool call id");
            }
            assembled.add(call);
        }
        return new Message(Message.Role.ASSISTANT, text.toString(), assembled, null);
    }

    public StreamChunk.Finished finished() {
        return finished;
    }

    private static void append(StringBuilder target, String part, int limit) {
        if (part != null) {
            if (target.length() + part.length() > limit) {
                throw new IllegalStateException("model output exceeds limit");
            }
            target.append(part);
        }
    }

    private static final class Parts {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
