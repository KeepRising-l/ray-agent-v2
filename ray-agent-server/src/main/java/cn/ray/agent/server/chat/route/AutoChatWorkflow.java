package cn.ray.agent.server.chat.route;

import cn.ray.agent.runtime.agent.RunBudget;
import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.server.chat.*;
import cn.ray.agent.server.rag.answer.GroundedAnswerService;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** AUTO 只决定并串联已有工作流；显式 CHAT/KNOWLEDGE 不会经过此类。 */
@Component
public class AutoChatWorkflow implements ChatWorkflow {
    private final WorkflowRunner runner;
    private final IntentRouter router;
    private final RouteTraceRecorder trace;
    private final GeneralChatWorkflow general;
    private final KnowledgeChatWorkflow knowledge;
    private final HybridChatWorkflow hybrid;

    public AutoChatWorkflow(WorkflowRunner runner, IntentRouter router, RouteTraceRecorder trace,
                            GeneralChatWorkflow general, KnowledgeChatWorkflow knowledge, HybridChatWorkflow hybrid) {
        this.runner = runner;
        this.router = router;
        this.trace = trace;
        this.general = general;
        this.knowledge = knowledge;
        this.hybrid = hybrid;
    }

    @Override
    public LlmAdapter bind(LlmAdapter model, Scope scope, RunBudget budget, Events events) {
        return runner.bind(model, budget, events, request -> execution -> {
            execution.event("ROUTE_STARTED", Map.of("hasKnowledgeScope", !scope.knowledgeBaseIds().isEmpty()));
            var decision = router.decide(execution, !scope.knowledgeBaseIds().isEmpty(),scope.policy().classifierEnabled());
            trace.decision(execution, decision);
            switch (decision.target()) {
                case CLARIFY -> {
                    if (decision.reason().equals("NO_KNOWLEDGE_SCOPE")) {
                        execution.text("这个问题需要你的资料。请先选择知识库并新建 AUTO 会话，再说明要查阅或比较什么。");
                    } else {
                        throw new cn.ray.agent.runtime.agent.UserQuestionRequired(new cn.ray.agent.runtime.agent.UserQuestionRequired.Continuation(
                                "AUTO_ROUTE", "请明确：你希望直接解释、查询所选知识库、搜索网页，还是比较资料与网页？", request, null));
                    }
                }
                case HYBRID -> {
                    if (execution.remainingModelCalls() < 1) {
                        execution.text("意图已识别，但剩余模型调用预算不足，尚未检索或联网，请调整预算后重试。");
                    } else {
                        hybrid.execute(execution, scope);
                    }
                }
                case DIRECT, WEB, KNOWLEDGE -> {
                    if (decision.target() == RouteDecision.Target.WEB && budget.toolCalls() == 0) {
                        execution.text("本次网页工具预算为0，尚未联网，无法核实外部最新信息；请调整工具预算后重试。");
                        return;
                    }
                    if (execution.remainingModelCalls() < 1) {
                        execution.text("意图已识别，但剩余模型调用预算不足，请调整预算后重试。");
                        return;
                    }
                    var childBudget = new RunBudget(execution.remainingModelCalls(), budget.toolCalls(), execution.remaining(),
                            budget.toolTimeoutMs(), budget.inputTokens());
                    var attemptedWeb = new AtomicBoolean();
                    var childEvents = new Events(event -> {
                        if (event.type().equals("TOOL_STARTED") && java.util.Set.of("web_search", "web_fetch").contains(event.name())) {
                            attemptedWeb.set(true);
                        }
                        events.tools().accept(event);
                    }, events.metadata());
                    LlmAdapter child = decision.target() == RouteDecision.Target.KNOWLEDGE
                            ? knowledge.bind(execution.model, scope, childBudget, childEvents)
                            : general.bind(execution.model, childBudget, childEvents, decision.target() == RouteDecision.Target.WEB,scope.policy().tools());
                    execution.collect(child, request, true);
                    if (decision.target() == RouteDecision.Target.WEB && !attemptedWeb.get()) {
                        throw new GroundedAnswerService.Failure("WEB_NOT_EXECUTED", "模型没有执行本次要求的网页工具，回答未作为已核实结果保存");
                    }
                }
            }
        });
    }
}
