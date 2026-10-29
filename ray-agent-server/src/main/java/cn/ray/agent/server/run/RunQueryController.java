package cn.ray.agent.server.run;

import cn.ray.agent.server.chat.ChatApplicationService;
import cn.ray.agent.server.conversation.ConversationRepository;
import cn.ray.agent.server.conversation.LocalOwner;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 查询运行状态、顺序事件和显式取消入口。 */
@RestController
@RequestMapping("/api/runs")
public class RunQueryController {
    private final ConversationRepository store;
    private final LocalOwner owner;
    private final ChatApplicationService chat;

    public RunQueryController(ConversationRepository store, LocalOwner owner, ChatApplicationService chat) {
        this.store = store;
        this.owner = owner;
        this.chat = chat;
    }

    @GetMapping("/{id}")
    public ConversationRepository.Run get(@PathVariable String id) {
        return store.run(owner.id(), id);
    }

    @GetMapping("/{id}/events")
    public List<ConversationRepository.Event> events(@PathVariable String id) {
        return store.events(owner.id(), id);
    }

    @PostMapping("/{id}/cancel")
    public ConversationRepository.Run cancel(@PathVariable String id) {
        return chat.cancel(id);
    }
}
