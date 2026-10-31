package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.tool.ToolResult;
import java.util.*;

/** 唯一支持的工具暂停点：模型单独提出 ask_user，尚未写入该工具的结果。
 * 之前步骤的观察、来源、预算均已冻结；恢复只补这个结果，不重放之前的工具。
 */
public record AgentCheckpoint(int version, GenerateRequest initial, List<Message> messages,
        List<ToolResult.Source> sources, Set<String> callIds, Set<String> allowed, RunBudget budget,
        int modelSteps, int toolCalls, long elapsedMs, TokenUsage usage, String questionCallId, String question) {
    public AgentCheckpoint {
        if (version != 1 || modelSteps < 1 || modelSteps >= budget.modelSteps() || toolCalls < 1
                || toolCalls > budget.toolCalls() || elapsedMs < 0 || question == null || question.isBlank() || question.length() > 600) {
            throw new IllegalArgumentException("invalid agent checkpoint");
        }
        messages = List.copyOf(messages);
        sources = List.copyOf(sources);
        callIds = Set.copyOf(callIds);
        allowed = Set.copyOf(allowed);
        if (!allowed.contains("ask_user") || !callIds.contains(questionCallId)) {
            throw new IllegalArgumentException("invalid question checkpoint");
        }
    }
}
