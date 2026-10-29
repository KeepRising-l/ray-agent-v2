package cn.ray.agent.runtime.tool;

import cn.ray.agent.runtime.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/** 定义给模型看的 Schema，并提供真正的 Java 执行入口。 */
public interface Tool {
    ToolDefinition definition();

    ToolResult execute(JsonNode arguments, ToolContext context) throws Exception;
}
