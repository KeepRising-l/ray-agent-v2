package cn.ray.agent.server.chat.route;

import cn.ray.agent.server.rag.answer.GroundedAnswerService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** 混合回答先校验三个结构化栏目，再展示；编号校验不能替代逐句语义事实核查。 */
@Component
public class SourceAttributionPolicy {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Pattern references = Pattern.compile("\\[([KW]\\d+)\\]");

    public AttributedAnswer validate(String json, HybridEvidencePlan plan) {
        try {
            var node = mapper.readTree(json);
            if (node == null || !node.isObject() || node.size() != 3) {
                throw invalid();
            }
            for (String field : List.of("knowledge", "web", "comparison")) {
                if (!node.path(field).isTextual() || node.path(field).asText().length() > 12000) {
                    throw invalid();
                }
            }
            Set<String> ks = plan.knowledge().texts().keySet();
            Set<String> ws = new HashSet<>();
            plan.webSources().forEach(source -> ws.add(source.id()));
            String knowledge = node.path("knowledge").asText();
            String web = node.path("web").asText();
            String comparison = node.path("comparison").asText();
            Set<String> usedK = check(knowledge, ks);
            Set<String> usedW = check(web, ws);
            Set<String> all = new HashSet<>(ks);
            all.addAll(ws);
            Set<String> usedComparison = check(comparison, all);
            if (!ks.isEmpty() && usedK.isEmpty() || !ws.isEmpty() && usedW.isEmpty()) {
                throw invalid();
            }
            usedComparison.stream().filter(id -> id.startsWith("K")).forEach(usedK::add);
            // 缺失的一类由代码固定说明，不能让模型用另一类来源填补。
            if (ks.isEmpty()) {
                knowledge = "所选知识库没有可用资料，本次没有知识库事实依据（" + plan.knowledgeStatus() + "）。";
            }
            if (ws.isEmpty()) {
                web = "本次未取得可用网页依据（" + plan.webStatus() + "），无法核实外部最新情况。";
            }
            if (ks.isEmpty() || ws.isEmpty()) {
                comparison = "两类证据未齐全，暂不能完成资料与网页的可靠对照。";
            } else if (usedComparison.stream().noneMatch(id -> id.startsWith("K"))
                    || usedComparison.stream().noneMatch(id -> id.startsWith("W"))) {
                throw invalid();
            }
            return new AttributedAnswer("知识库资料\n" + knowledge + "\n\n网页资料\n" + web + "\n\n比较与局限\n" + comparison, Set.copyOf(usedK));
        } catch (java.io.IOException e) {
            throw invalid();
        }
    }

    private Set<String> check(String text, Set<String> allowed) {
        Set<String> used = new LinkedHashSet<>();
        var matcher = references.matcher(text);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw invalid();
            }
            used.add(matcher.group(1));
        }
        return used;
    }

    private GroundedAnswerService.Failure invalid() {
        return new GroundedAnswerService.Failure("INVALID_ATTRIBUTION", "混合回答的栏目或来源校验未通过，未发布该回答");
    }

    public record AttributedAnswer(String text, Set<String> knowledgeIds) { }
}
