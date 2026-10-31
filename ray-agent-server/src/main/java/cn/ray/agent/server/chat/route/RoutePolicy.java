package cn.ray.agent.server.chat.route;

import org.springframework.stereotype.Component;
import java.util.*;
import static cn.ray.agent.server.chat.route.RouteDecision.Target.*;

/** 规则只处理明确意图，其余交给有限分类；资料范围为空时不能路由到检索。 */
@Component
public class RoutePolicy {
    public Optional<RouteDecision> rule(String question, boolean hasScope) {
        String value = question.strip().toLowerCase(Locale.ROOT);
        if (value.matches("(你好|您好|嗨|早上好|晚上好|谢谢|hi|hello|thanks)[！!。 .]*")) {
            return Optional.of(new RouteDecision(DIRECT, "RULE", "GREETING"));
        }
        boolean knowledge = value.matches("(?s).*(知识库|我的资料|我的文档|所选资料|上传的|项目资料).*");
        boolean web = value.matches("(?s).*(联网|网上|网页|搜索|查一下最新|最新|今天|实时).*");
        boolean denyWeb = value.matches("(?s).*(不要联网|不联网|禁止联网|不能联网|不要搜索|不搜索|只看(知识库|我的资料|所选资料)).*");
        boolean denyKnowledge = value.matches("(?s).*(不读(取)?知识库|不要(读|读取|查|查询)知识库).*");
        if (denyWeb) {
            return Optional.of(guard(new RouteDecision(knowledge && !denyKnowledge ? KNOWLEDGE : DIRECT,
                    "RULE", "WEB_DISABLED_BY_USER"), hasScope));
        }
        if (denyKnowledge) {
            return Optional.of(new RouteDecision(web ? WEB : DIRECT, "RULE", "KNOWLEDGE_DISABLED_BY_USER"));
        }
        if (!knowledge && !web && value.matches("(?s)(直接解释|直接回答|只用常识).+")) {
            return Optional.of(new RouteDecision(DIRECT, "RULE", "DIRECT_REQUEST"));
        }
        if (knowledge) {
            return Optional.of(guard(new RouteDecision(web ? HYBRID : KNOWLEDGE, "RULE", "EXPLICIT_SCOPE"), hasScope));
        }
        if (web) {
            return Optional.of(new RouteDecision(WEB, "RULE", "FRESH_INFORMATION"));
        }
        return Optional.empty();
    }

    public RouteDecision guard(RouteDecision decision, boolean hasScope) {
        if (!hasScope && (decision.target() == KNOWLEDGE || decision.target() == HYBRID)) {
            return new RouteDecision(CLARIFY, "POLICY", "NO_KNOWLEDGE_SCOPE");
        }
        return decision;
    }

    public RouteDecision fallback() {
        return new RouteDecision(CLARIFY, "FALLBACK", "CLASSIFIER_UNAVAILABLE");
    }
}
