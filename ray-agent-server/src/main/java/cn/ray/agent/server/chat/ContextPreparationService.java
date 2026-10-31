package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.context.*;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.chat.route.WorkflowRunner;
import cn.ray.agent.server.conversation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CancellationException;

/** 在SSE建立后的有界工作线程整理上下文；摘要、分类与正式生成共享模型次数和总时限。 */
@Service
public class ContextPreparationService {
    private final WorkflowRunner runner;
    private final ModeDispatcher modes;
    private final SessionRestoreService restore;
    private final CompactionRecorder recorder;
    private final CompactionPolicy policy;
    private final CompactionEngine engine = new CompactionEngine();

    public ContextPreparationService(WorkflowRunner runner, ModeDispatcher modes, SessionRestoreService restore,
                                     CompactionRecorder recorder, @Value("${ray.context.compaction-trigger:14000}") int threshold,
                                     @Value("${ray.context.keep-recent-turns:4}") int keep) {
        this.runner = runner;
        this.modes = modes;
        this.restore = restore;
        this.recorder = recorder;
        this.policy = new CompactionPolicy(threshold, keep, 12000, 1600);
    }

    public LlmAdapter bind(LlmAdapter model, String session, String run, ChatMode mode, ChatWorkflow.Scope scope,
                           RunBudget budget, ChatWorkflow.Events events) {
        return runner.bind(model, budget, events, request -> execution -> {
            execution.event("RUN_PREPARING_CONTEXT", Map.of());
            var view = restore.restore(scope.ownerId(), session);
            var history = view.messages();
            int estimated = history.stream().mapToInt(new TokenEstimator()::estimate).sum()
                    + request.messages().stream().mapToInt(new TokenEstimator()::estimate).sum();
            if (estimated >= policy.triggerTokens() && view.turns().size() - policy.keepRecentTurns() > view.covered()
                    && execution.remainingModelCalls() > 1) {
                int end = view.covered();
                String previous = view.valid() && view.saved() != null ? view.saved().summary() : "";
                int size = previous.isEmpty() ? 0 : new TokenEstimator().estimate(new Message(Message.Role.USER, previous));
                var newlyCovered = new ArrayList<Message>();
                while (end < view.turns().size() - policy.keepRecentTurns()) {
                    var next = view.turns().get(end).messages();
                    int nextSize = next.stream().mapToInt(new TokenEstimator()::estimate).sum();
                    if (size + nextSize > policy.maxSourceTokens()) { break; }
                    newlyCovered.addAll(next);
                    size += nextSize;
                    end++;
                }
                if (end > view.covered()) {
                    execution.event("COMPACTION_STARTED", Map.of("oldCoveredTurns", view.covered(), "newCoveredTurns", end));
                    try {
                        var result = execution.collect(execution.model, engine.request(previous, newlyCovered, policy), false);
                        var evaluated = engine.evaluate(result.message().content(), previous, newlyCovered, policy);
                        boolean published = false;
                        if (evaluated.accepted()) {
                            var prefix = view.turns().subList(0,end);
                            var range = new SummaryRange(prefix.stream().map(SessionRebuilder.Turn::runId).toList(), SessionRebuilder.hash(prefix));
                            published = recorder.publish(scope.ownerId(), session, run, view.saved() == null ? 0 : view.saved().revision(), range, evaluated.summary());
                        }
                        execution.event(published ? "COMPACTION_APPLIED" : "COMPACTION_SKIPPED", Map.of("reason", evaluated.reason(),
                                "beforeTokens", evaluated.beforeTokens(), "afterTokens", evaluated.afterTokens(), "coveredTurns", end));
                        if (published) { history = restore.restore(scope.ownerId(), session).messages(); }
                    } catch (InterruptedException | CancellationException e) {
                        throw e;
                    } catch (Exception e) {
                        execution.remaining();
                        execution.event("COMPACTION_FALLBACK", Map.of("reason", "SUMMARY_FAILED"));
                    }
                }
            }
            var messages = new ArrayList<Message>();
            messages.add(request.messages().get(0));
            messages.addAll(history);
            messages.add(request.messages().get(request.messages().size()-1));
            var selected = new ModeContextPolicy().select(mode, messages);
            execution.event("CONTEXT_PROJECTED", Map.of("estimatedInput", selected.estimatedInputTokens(), "omittedTurns", selected.omittedTurns()));
            if (execution.remainingModelCalls() < 1) {
                throw new IllegalArgumentException("model budget exhausted during compaction");
            }
            var childBudget = new RunBudget(execution.remainingModelCalls(), budget.toolCalls(), execution.remaining(), budget.toolTimeoutMs(), budget.inputTokens());
            var child = modes.bind(mode, execution.model, scope, childBudget, events);
            execution.collect(child, new GenerateRequest(selected.messages(), request.options(), List.of()), true);
        }, "RUN");
    }
}
