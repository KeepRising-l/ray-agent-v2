package cn.ray.agent.server.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 用户显式选择的会话边界，AUTO 内部的意图目标不能反过来修改它。 */
public enum ChatMode {
    CHAT, KNOWLEDGE, AUTO;

    public static ChatMode parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模式必须是 CHAT、KNOWLEDGE 或 AUTO");
        }
    }
}
