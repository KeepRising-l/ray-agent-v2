package cn.ray.agent.server.chat;

import cn.ray.agent.server.conversation.LocalOwner;
import cn.ray.agent.server.rag.answer.GroundedAnswerService;
import org.springframework.web.bind.annotation.*;

@RestController
public class CitationController {
    private final LocalOwner owner;
    private final GroundedAnswerService answers;

    public CitationController(LocalOwner owner, GroundedAnswerService answers) {
        this.owner = owner;
        this.answers = answers;
    }

    @GetMapping("/api/runs/{run}/citations/{id}")
    public GroundedAnswerService.CitationView citation(@PathVariable String run, @PathVariable String id) {
        return answers.citation(owner.id(), run, id);
    }
}
