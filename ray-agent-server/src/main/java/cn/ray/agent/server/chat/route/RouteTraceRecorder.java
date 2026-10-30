package cn.ray.agent.server.chat.route;

import org.springframework.stereotype.Component;
import java.util.Map;

/** 路由只保存目标、规则原因和来源类型；沿用运行事件落库/SSE，不另建第二套运行记录。 */
@Component
public class RouteTraceRecorder {
    public void decision(WorkflowRunner.Session execution, RouteDecision decision) {
        execution.event("ROUTE_DECIDED", Map.of("target", decision.target().name(), "origin", decision.origin(), "reason", decision.reason()));
    }
}
