package cn.ray.agent.server.rag.answer;

import cn.ray.agent.runtime.context.*;
import cn.ray.agent.runtime.model.Message;
import cn.ray.agent.server.rag.answer.EvidenceAssembler.Evidence;
import java.util.LinkedHashSet;

/** 按实际序列化后的保守估算选择完整片段，不截断引用原文，也不裁掉用户最新问题。 */
public final class EvidenceBudgetPolicy {
    public Evidence fit(String question, Evidence evidence, int allowance) {
        int questionCost = new TokenEstimator().estimate(new Message(Message.Role.USER, question));
        int available = Math.min(allowance, ContextAllocationPolicy.INPUT_TOKENS-questionCost-3500);
        var selected = new LinkedHashSet<String>();
        for (var source : evidence.citations()) {
            selected.add(source.id());
            int cost = new TokenEstimator().estimate(new Message(Message.Role.USER, evidence.select(selected).prompt()));
            if (cost > available) { selected.remove(source.id()); }
        }
        return evidence.select(selected);
    }
}
