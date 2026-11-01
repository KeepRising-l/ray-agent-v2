package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.runtime.model.*;
import java.util.List;

/** 受限的指代补全：仅拼接最近一条用户问题，不采用助手答案，不让历史指令改变资料范围。 */
public final class QueryRewriter {
    public Rewrite rewrite(GenerateRequest request) {
        List<String> questions = request.messages().stream().filter(message -> message.role() == Message.Role.USER)
                .map(Message::content).toList();
        String current = questions.get(questions.size()-1);
        if (questions.size() < 2 || !current.matches("^(它|这个|那个|该项目|该规则|那么|那它).{0,200}$")) {
            return new Rewrite(current, current, false);
        }
        String previous = questions.get(questions.size()-2);
        String combined = "上一条用户问题：" + previous + "\n当前用户问题：" + current;
        if (combined.length() > 4000) return new Rewrite(current, current, false);
        return new Rewrite(current, combined, true);
    }
    public record Rewrite(String original, String query, boolean changed) { }
}
