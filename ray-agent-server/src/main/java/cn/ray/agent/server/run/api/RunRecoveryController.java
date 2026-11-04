package cn.ray.agent.server.run.api;

import cn.ray.agent.server.run.question.PendingQuestionStore;

import cn.ray.agent.server.run.admission.RequestIdempotencyService;

import cn.ray.agent.server.conversation.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 恢复方式按检查点能力返回；中断聊天只可重新发起，不提供整轮自动重跑按钮。 */
@RestController
public class RunRecoveryController {
    private final ConversationRepository store;private final PendingQuestionStore questions;
    private final RequestIdempotencyService requests;private final SessionRegistry registry;private final LocalOwner owner;
    public RunRecoveryController(ConversationRepository store,PendingQuestionStore questions,RequestIdempotencyService requests,SessionRegistry registry,LocalOwner owner){this.store=store;this.questions=questions;this.requests=requests;this.registry=registry;this.owner=owner;}
    @GetMapping("/api/runs/{id}/recovery") public Map<String,Object> recovery(@PathVariable String id){
        questions.expire();var run=store.run(owner.id(),id);var question=questions.question(owner.id(),id);
        boolean waiting=run.status().equals("WAITING_USER")&&question!=null&&question.status().equals("WAITING_USER");
        return Map.of("runId",id,"status",run.status(),"attached",registry.attached(owner.id(),id),"action",waiting?"ANSWER_PENDING":"NONE",
                "questionId",waiting?question.id():"","message",waiting?"回答原提问即可继续此run":"当前状态没有可恢复执行的检查点；历史与上下文仍可查询");
    }
    @GetMapping("/api/sessions/{session}/requests/{request}") public ConversationRepository.Run request(@PathVariable String session,@PathVariable String request){return requests.find(owner.id(),session,request);}
}
