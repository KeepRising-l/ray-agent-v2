package cn.ray.agent.server.run.api;

import cn.ray.agent.server.run.question.PendingQuestionStore;

import cn.ray.agent.server.chat.ChatApplicationService;
import cn.ray.agent.server.conversation.LocalOwner;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 路径绑定runId，正文绑定questionId；身份只使用服务端owner，不接受客户端伪造。 */
@RestController
@RequestMapping("/api/runs/{runId}")
public class UserAnswerController {
    private final PendingQuestionStore questions;
    private final ChatApplicationService chats;
    private final LocalOwner owner;

    public UserAnswerController(PendingQuestionStore questions, ChatApplicationService chats, LocalOwner owner) {
        this.questions = questions;
        this.chats = chats;
        this.owner = owner;
    }

    @GetMapping("/question")
    public State question(@PathVariable String runId) {
        questions.expire();
        return new State(questions.question(owner.id(), runId));
    }

    @PostMapping(value="/answers", produces="text/event-stream")
    public SseEmitter answer(@PathVariable String runId, @RequestBody Answer answer) {
        return chats.resume(runId, answer.questionId(), answer.answer());
    }

    public record Answer(String questionId, String answer) { }
    public record State(PendingQuestionStore.Question question) { }
}
