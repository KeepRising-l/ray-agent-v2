package cn.ray.agent.server.chat.route;

/** 可执行目标与简短原因码；不保存模型自由解释或内部思考。 */
public record RouteDecision(Target target, String origin, String reason) {
    public enum Target { DIRECT, KNOWLEDGE, WEB, HYBRID, CLARIFY }
}
