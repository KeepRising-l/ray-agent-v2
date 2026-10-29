package cn.ray.agent.server.chat;

import cn.ray.agent.server.model.ModelChannelService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** 最小聊天入口：接收一条文本，返回逐段输出的业务事件。 */
@RestController
public class ChatController {
    private final ChatApplicationService chatService;
    private final ModelChannelService channelService;

    public ChatController(ChatApplicationService chatService, ModelChannelService channelService) {
        this.chatService = chatService;
        this.channelService = channelService;
    }

    /** 列出本机已配置的渠道名称和模型名，不返回地址或密钥。 */
    @GetMapping("/api/model-channels")
    public List<ModelChannelService.ChannelView> channels() {
        return channelService.list();
    }

    /** @param request 用户本次输入；当前不接收会话历史和工具。 */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        if (request.message().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能超过 4000 个字符");
        }
        if (request.clientRequestId() != null && (request.clientRequestId().isBlank() || request.clientRequestId().length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求编号无效");
        }
        if (request.sessionId() != null && !request.sessionId().matches("[a-f0-9-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话编号无效");
        }
        return chatService.stream(request.message(), request.channelId(), request.sessionId(), request.clientRequestId());
    }

    /** 浏览器提交的消息。 */
    public record ChatRequest(String message, String channelId, String sessionId, String clientRequestId) { }
}
