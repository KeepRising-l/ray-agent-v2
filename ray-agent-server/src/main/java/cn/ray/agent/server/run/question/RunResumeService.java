package cn.ray.agent.server.run.question;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.chat.*;
import cn.ray.agent.server.chat.route.WorkflowRunner;
import org.springframework.stereotype.Service;
import java.util.*;

/** 只恢复已持久化的两种检查点。等待时间不计活跃预算，旧工具不会从头执行。 */
@Service
public class RunResumeService {
    private final WorkflowRunner runner;
    private final ReactLoopAgent agent;
    private final ModeDispatcher modes;

    public RunResumeService(WorkflowRunner runner, ReactLoopAgent agent, ModeDispatcher modes) {
        this.runner = runner;
        this.agent = agent;
        this.modes = modes;
    }

    public LlmAdapter bind(LlmAdapter model, PendingQuestionStore.Accepted accepted, ChatMode mode,
                           ChatWorkflow.Scope scope, ChatWorkflow.Events events) {
        var saved = accepted.snapshot();
        var original = saved.budget();
        int calls = original.modelSteps() - saved.modelCalls();
        long time = original.timeoutMs() - saved.activeMs();
        if (calls < 1 || time < 1) {
            throw new IllegalArgumentException("resume budget exhausted");
        }
        var budget = new RunBudget(calls, original.toolCalls(), time, original.toolTimeoutMs(), original.inputTokens());
        return runner.bind(model, budget, events, ignored -> execution -> {
            execution.event("RUN_RESUMED", Map.of("kind", saved.continuation().kind()));
            if (saved.continuation().kind().equals("AGENT")) {
                if (mode == ChatMode.KNOWLEDGE) { throw new IllegalArgumentException("knowledge cannot resume tools"); }
                var checkpoint = saved.continuation().agent();
                var attemptedWeb = new java.util.concurrent.atomic.AtomicBoolean(checkpoint.messages().stream()
                        .flatMap(message -> message.toolCalls().stream()).anyMatch(call -> Set.of("web_search", "web_fetch").contains(call.name())));
                var child = agent.resume(execution.model, checkpoint, accepted.answer(), event -> {
                    if (event.type().equals("TOOL_STARTED") && Set.of("web_search", "web_fetch").contains(event.name())) {
                        attemptedWeb.set(true);
                    }
                    events.tools().accept(event);
                });
                execution.collect(child, saved.continuation().request(), true);
                if (mode == ChatMode.AUTO && checkpoint.allowed().contains("web_search") && !attemptedWeb.get()) {
                    throw new cn.ray.agent.server.rag.answer.GroundedAnswerService.Failure("WEB_NOT_EXECUTED", "恢复后仍未执行要求的网页工具，回答未作为已核实结果保存");
                }
            } else {
                if (mode != ChatMode.AUTO) { throw new IllegalArgumentException("route checkpoint requires AUTO"); }
                var request = saved.continuation().request();
                var messages = new ArrayList<>(request.messages());
                // 原问题、助手提问、用户补充各保留角色；最新明确回答不能再被原来的歧义指令盖住。
                messages.add(new Message(Message.Role.ASSISTANT, saved.continuation().question()));
                messages.add(new Message(Message.Role.USER, accepted.answer()));
                execution.collect(modes.bind(mode, execution.model, scope, budget, events),
                        new GenerateRequest(messages, request.options(), List.of()), true);
            }
        }, "RUN");
    }
}
