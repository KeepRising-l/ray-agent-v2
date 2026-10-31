package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.context.*;
import cn.ray.agent.runtime.model.*;
import java.util.List;

/** 最新问题不可裁剪；资料模式先预留证据份额，工具调用与结果仍按完整轮次选择。 */
public final class ModeContextPolicy {
    public ContextAssembler.Selection select(ChatMode mode, List<Message> messages) {
        int budget = new ContextAllocationPolicy().historyBudget(mode != ChatMode.CHAT);
        return new ContextAssembler().assemble(messages, new ContextBudget(budget, 2048));
    }
}
