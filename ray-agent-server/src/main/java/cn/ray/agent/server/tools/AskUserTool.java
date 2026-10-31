package cn.ray.agent.server.tools;

import cn.ray.agent.runtime.model.ToolDefinition;
import cn.ray.agent.runtime.tool.*;
import com.fasterxml.jackson.databind.JsonNode;

/** 返回可持久化的提问意图；不在工具线程里等待用户输入。 */
public final class AskUserTool implements Tool {
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("ask_user", "缺少必要参数或需求含糊时向用户提问。必须单独调用，不能与其他工具同批。",
                """
                {"type":"object","properties":{"question":{"type":"string","maxLength":600}},"required":["question"],"additionalProperties":false}
                """);
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        context.checkCancelled();
        return new ToolResult(true, "WAITING_USER", arguments.toString(), java.util.List.of());
    }
}
