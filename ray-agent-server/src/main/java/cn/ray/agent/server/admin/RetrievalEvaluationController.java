package cn.ray.agent.server.admin;

import cn.ray.agent.server.rag.retrieve.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 个人管理员对同一评估问题测基线/关键词/RRF；只做授权查询，不保存全文或改变线上策略。 */
@RestController
@RequestMapping("/api/admin/retrieval")
public class RetrievalEvaluationController {
    private final AdminAccessPolicy access;
    private final RetrievalService retrieval;
    public RetrievalEvaluationController(AdminAccessPolicy access, RetrievalService retrieval) {
        this.access = access;
        this.retrieval = retrieval;
    }
    @PostMapping("/compare")
    public HybridRetriever.Comparison compare(@RequestBody Input input) {
        return retrieval.compare(new RetrievalQuery(input.question(), new RetrievalFilter(access.owner(), input.knowledgeBaseIds()), input.limit(), null));
    }
    public record Input(String question, List<String> knowledgeBaseIds, int limit) { }
}
