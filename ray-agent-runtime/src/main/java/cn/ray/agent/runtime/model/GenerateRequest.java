package cn.ray.agent.runtime.model;

import java.util.List;
import java.util.Objects;

/** 一次模型生成的输入：对话消息、生成选项和允许模型使用的工具定义。 */
public record GenerateRequest(List<Message> messages, GenerateOptions options, List<ToolDefinition> tools) {
    /** 只有消息时，不指定生成选项，也不给模型提供工具。 */
    public GenerateRequest(List<Message> messages) {
        this(messages, new GenerateOptions(null, null, null), List.of());
    }

    /**
     * @param messages 按顺序发送给模型的消息，至少一条
     * @param options 生成选项，未指定的单项使用 null
     * @param tools 本次允许模型看到的工具定义，可以为空列表
     */
    public GenerateRequest {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        options = Objects.requireNonNull(options, "options must not be null");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }
}
