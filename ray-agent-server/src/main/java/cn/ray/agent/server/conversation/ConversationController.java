package cn.ray.agent.server.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** 本机会话查询入口；身份始终由服务端提供，当前尚未接入登录。 */
@RestController
@RequestMapping("/api/sessions")
public class ConversationController {
    private final ConversationRepository store;
    private final LocalOwner owner;

    public ConversationController(ConversationRepository store, LocalOwner owner) {
        this.store = store;
        this.owner = owner;
    }

    @GetMapping
    public List<ConversationRepository.Session> list() {
        return store.sessions(owner.id());
    }

    @PostMapping
    public ConversationRepository.Session create(@RequestBody CreateSession request) {
        if (request.title() == null || request.title().isBlank() || request.title().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题需为 1 到 100 字符");
        }
        return store.create(owner.id(), request.title());
    }

    @GetMapping("/{id}/messages")
    public List<ConversationRepository.ChatMessage> messages(@PathVariable String id) {
        return store.messages(owner.id(), id);
    }

    @GetMapping("/{id}/runs")
    public List<ConversationRepository.Run> runs(@PathVariable String id) {
        return store.runs(owner.id(), id);
    }

    public record CreateSession(String title) { }
}
