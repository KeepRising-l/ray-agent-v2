package cn.ray.agent.runtime.context;

import cn.ray.agent.runtime.model.Message;
import java.nio.charset.StandardCharsets;

/** 用 UTF-8 字节数加消息开销估算输入成本，偏保守；不冒充供应商 tokenizer。 */
public final class TokenEstimator {
    public int estimate(Message message) {
        long size = 8;
        if (message.content() != null) {
            size += message.content().getBytes(StandardCharsets.UTF_8).length;
        }
        for (var call : message.toolCalls()) {
            size += call.id().length() + call.name().length()
                    + call.argumentsJson().getBytes(StandardCharsets.UTF_8).length;
        }
        if (message.toolCallId() != null) {
            size += message.toolCallId().length();
        }
        return (int) Math.min(Integer.MAX_VALUE, size);
    }
}
