package cn.ray.agent.runtime.model;

import java.util.List;
import java.util.Objects;

/** 一条对话消息；工具调用属于助手消息，工具结果使用对应的调用 ID。 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    /** 创建普通文本消息，保留此前的使用方式。 */
    public Message(Role role, String content) {
        this(role, content, List.of(), null);
    }

    /**
     * @param role 消息在对话中的角色，不是登录身份
     * @param content 文本内容；助手仅发起工具调用时可为空
     * @param toolCalls 助手发起的完整工具调用，其他角色必须为空
     * @param toolCallId 工具结果对应的调用 ID，仅 TOOL 消息使用
     */
    public Message {
        Objects.requireNonNull(role, "role must not be null");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages can contain tool calls");
        }
        if (role == Role.TOOL) {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("tool messages require a call id");
            }
        } else if (toolCallId != null) {
            throw new IllegalArgumentException("only tool messages can contain a call id");
        }
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("message needs text or a tool call");
        }
    }
}
