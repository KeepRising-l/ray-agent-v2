package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.server.rag.answer.RagStreamEventMapper;
import java.util.List;
import java.util.function.Consumer;

/** 三模式共用模型、预算与事件契约；owner 和资料范围只能来自已校验的服务端会话。 */
public interface ChatWorkflow {
    LlmAdapter bind(LlmAdapter model, Scope scope, RunBudget budget, Events events);

    record Scope(String ownerId, List<String> knowledgeBaseIds, cn.ray.agent.server.admin.RuntimePolicyService.Policy policy) {
        public Scope(String ownerId,List<String> knowledgeBaseIds) { this(ownerId,knowledgeBaseIds,cn.ray.agent.server.admin.RuntimePolicyService.Policy.defaults()); }
        public Scope {
            knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        }
    }

    record Events(AgentEventSink tools, Consumer<RagStreamEventMapper.Event> metadata) { }
}
