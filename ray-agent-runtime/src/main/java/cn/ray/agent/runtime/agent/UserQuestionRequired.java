package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.model.GenerateRequest;

/** 暂停是一种控制结果，不是模型失败；由server保存检查点并结束本次SSE连接。 */
public final class UserQuestionRequired extends RuntimeException {
    private final Continuation continuation;

    public UserQuestionRequired(Continuation continuation) {
        super("waiting for user");
        this.continuation = continuation;
    }

    public Continuation continuation() {
        return continuation;
    }

    public record Continuation(String kind, String question, GenerateRequest request, AgentCheckpoint agent) {
        public Continuation {
            if ((!kind.equals("AGENT") && !kind.equals("AUTO_ROUTE")) || question == null || question.isBlank()
                    || question.length() > 600 || request == null || (kind.equals("AGENT") && agent == null)) {
                throw new IllegalArgumentException("invalid continuation");
            }
        }
    }
}
