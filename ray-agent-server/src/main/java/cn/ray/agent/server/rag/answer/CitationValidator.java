package cn.ray.agent.server.rag.answer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.regex.Pattern;

/** 校验依据编号与逐字摘录，再校验最终引用；不把编号合法冒充每句话都已语义核验。 */
public final class CitationValidator {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Decision decision(String json, EvidenceAssembler.Evidence evidence) {
        try {
            String input = json.strip();
            if (input.startsWith("```json\n") && input.endsWith("```")) {
                input = input.substring(8, input.length() - 3).strip();
            }
            var node = mapper.readTree(input);
            if (node == null || !node.isObject() || node.size() != 2 || !node.path("answerable").isBoolean()
                    || !node.path("sources").isArray() || node.path("sources").size() > evidence.citations().size()) {
                throw failure();
            }
            Set<String> ids = new LinkedHashSet<>();
            for (var source : node.path("sources")) {
                String id = source.path("id").asText();
                String quote = source.path("quote").asText();
                String text = evidence.texts().get(id);
                if (!source.isObject() || source.size() != 2 || !source.path("id").isTextual() || !source.path("quote").isTextual()
                        || text == null || normalize(quote).length() < 2 || quote.length() > 200
                        || !normalize(text).contains(normalize(quote)) || !ids.add(id)) {
                    throw failure();
                }
            }
            boolean supported = node.path("answerable").asBoolean();
            if (supported == ids.isEmpty()) {
                throw failure();
            }
            return new Decision(supported, Set.copyOf(ids));
        } catch (java.io.IOException e) {
            throw failure();
        }
    }

    public Set<String> validateAnswer(String answer, EvidenceAssembler.Evidence evidence) {
        Set<String> ids = new LinkedHashSet<>();
        var references = Pattern.compile("\\[(K\\d+)\\]").matcher(answer);
        while (references.find()) {
            if (!evidence.texts().containsKey(references.group(1))) {
                throw new GroundedAnswerService.Failure("INVALID_CITATION", "回答引用了本轮不存在的知识库片段");
            }
            ids.add(references.group(1));
        }
        if (ids.isEmpty() || Pattern.compile("\\[W\\d+\\]").matcher(answer).find()) {
            throw new GroundedAnswerService.Failure("INVALID_CITATION", "知识库回答缺少有效资料引用");
        }
        return Set.copyOf(ids);
    }

    private String normalize(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private GroundedAnswerService.Failure failure() {
        return new GroundedAnswerService.Failure("INVALID_EVIDENCE_DECISION", "模型未返回有效的证据判断，未生成无依据回答");
    }

    public record Decision(boolean answerable, Set<String> sourceIds) { }
}
