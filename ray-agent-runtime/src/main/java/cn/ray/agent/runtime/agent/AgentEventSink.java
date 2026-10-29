package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.tool.ToolResult;
import java.util.List;

/** 运行内核只报告事实；数据库和 SSE 的具体实现由 server 提供。 */
@FunctionalInterface
public interface AgentEventSink {
    void accept(Event event);

    record Event(String type, int step, String callId, String name, String code,
                 long durationMs, List<ToolResult.Source> sources) {
        public Event {
            sources = List.copyOf(sources);
        }
    }
}
