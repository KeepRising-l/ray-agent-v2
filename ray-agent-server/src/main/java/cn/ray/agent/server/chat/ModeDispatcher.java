package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.agent.RunBudget;
import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.server.chat.route.AutoChatWorkflow;
import org.springframework.stereotype.Component;

/** 显式模式分派在最外层完成，分类器只属于 AUTO；不允许非法模式静默降级。 */
@Component
public class ModeDispatcher {
    private final GeneralChatWorkflow general;
    private final KnowledgeChatWorkflow knowledge;
    private final AutoChatWorkflow auto;

    public ModeDispatcher(GeneralChatWorkflow general, KnowledgeChatWorkflow knowledge, AutoChatWorkflow auto) {
        this.general = general;
        this.knowledge = knowledge;
        this.auto = auto;
    }

    public LlmAdapter bind(ChatMode mode, LlmAdapter model, ChatWorkflow.Scope scope,
                           RunBudget budget, ChatWorkflow.Events events) {
        return switch (mode) {
            case CHAT -> general.bind(model, scope, budget, events);
            case KNOWLEDGE -> knowledge.bind(model, scope, budget, events);
            case AUTO -> auto.bind(model, scope, budget, events);
        };
    }
}
