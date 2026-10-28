package cn.ray.agent.runtime.model;

import java.util.Objects;

/** 模型给出的完整工具调用：调用 ID、工具名和实际参数 JSON。 */
public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(argumentsJson, "argumentsJson must not be null");
        if (id.isBlank() || name.isBlank() || argumentsJson.isBlank()) {
            throw new IllegalArgumentException("tool call fields must not be blank");
        }
        // 参数 JSON 仍需执行前解析、校验并检查权限；这里不执行工具。
    }
}
