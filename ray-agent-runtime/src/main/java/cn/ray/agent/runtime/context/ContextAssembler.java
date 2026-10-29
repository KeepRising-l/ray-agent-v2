package cn.ray.agent.runtime.context;

import cn.ray.agent.runtime.model.Message;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 保留系统约束和近期完整轮次；工具调用与对应结果作为同一轮一起保留或舍弃。 */
public final class ContextAssembler {
    private final TokenEstimator estimator = new TokenEstimator();

    public Selection assemble(List<Message> history, ContextBudget budget) {
        List<Message> system = new ArrayList<>();
        List<List<Message>> turns = new ArrayList<>();
        for (Message message : history) {
            if (message.role() == Message.Role.SYSTEM) {
                if (!turns.isEmpty()) {
                    throw new IllegalArgumentException("system messages must precede conversation");
                }
                system.add(message);
            } else {
                if (message.role() == Message.Role.USER) {
                    turns.add(new ArrayList<>());
                }
                if (turns.isEmpty()) {
                    throw new IllegalArgumentException("conversation must start with a user message");
                }
                turns.get(turns.size() - 1).add(message);
            }
        }
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("at least one user message is required");
        }
        for (List<Message> turn : turns) {
            validateTools(turn);
        }
        long cost = estimate(system);
        List<List<Message>> selected = new ArrayList<>();
        for (int i = turns.size() - 1; i >= 0; i--) {
            long next = estimate(turns.get(i));
            if (cost + next > budget.maxInputTokens()) {
                if (i == turns.size() - 1) {
                    throw new IllegalArgumentException("current input exceeds context budget");
                }
                break;
            }
            cost += next;
            selected.add(0, turns.get(i));
        }
        List<Message> messages = new ArrayList<>(system);
        selected.forEach(messages::addAll);
        return new Selection(List.copyOf(messages), (int) cost, turns.size() - selected.size());
    }

    private long estimate(List<Message> messages) {
        return messages.stream().mapToLong(estimator::estimate).sum();
    }

    private void validateTools(List<Message> turn) {
        var pending = new HashSet<String>();
        for (Message message : turn) {
            if (!pending.isEmpty() && message.role() != Message.Role.TOOL) {
                throw new IllegalArgumentException("tool calls must have matching results");
            }
            for (var call : message.toolCalls()) {
                if (!pending.add(call.id())) {
                    throw new IllegalArgumentException("duplicate tool call id");
                }
            }
            if (message.role() == Message.Role.TOOL && !pending.remove(message.toolCallId())) {
                throw new IllegalArgumentException("orphan or duplicate tool result");
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("missing tool results");
        }
    }

    public record Selection(List<Message> messages, int estimatedInputTokens, int omittedTurns) { }
}
