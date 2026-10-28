package cn.ray.agent.runtime.model;

import java.util.Objects;

/** 提供给模型的工具说明；参数结构以 JSON Schema 字符串表达。 */
public record ToolDefinition(String name, String description, String parametersSchemaJson) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(parametersSchemaJson, "parametersSchemaJson must not be null");
        if (name.isBlank() || description.isBlank() || parametersSchemaJson.isBlank()) {
            throw new IllegalArgumentException("tool definition fields must not be blank");
        }
        // 此处只保存文本；JSON Schema 的格式及工具授权由后续实现负责校验。
    }
}
