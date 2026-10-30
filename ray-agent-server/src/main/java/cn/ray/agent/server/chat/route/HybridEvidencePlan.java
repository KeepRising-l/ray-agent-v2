package cn.ray.agent.server.chat.route;

import cn.ray.agent.runtime.tool.ToolResult;
import cn.ray.agent.server.rag.answer.EvidenceAssembler.Evidence;
import java.util.List;

/** 本次混合请求的两类证据及失败码；网页查询只能来自用户问题，不能夹带检索正文。 */
public record HybridEvidencePlan(Evidence knowledge, List<ToolResult> web, String knowledgeStatus, String webStatus) {
    public HybridEvidencePlan {
        web = List.copyOf(web);
    }

    public List<ToolResult.Source> webSources() {
        return web.stream().filter(ToolResult::success).flatMap(result -> result.sources().stream()).distinct().toList();
    }
}
