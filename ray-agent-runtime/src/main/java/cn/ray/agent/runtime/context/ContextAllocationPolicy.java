package cn.ray.agent.runtime.context;

/** 整体输入仍需最后校验；这些是证据/工具的份额，不能靠裁掉当前问题或拆散工具配对凑预算。 */
public final class ContextAllocationPolicy {
    public static final int INPUT_TOKENS = 24000;
    public static final int EVIDENCE_TOKENS = 8000;
    public static final int TOOL_RESULT_CHARACTERS = 1200;

    public int historyBudget(boolean usesKnowledge) {
        return usesKnowledge ? INPUT_TOKENS - EVIDENCE_TOKENS : INPUT_TOKENS;
    }

    public String toolText(String text) {
        if (text.codePointCount(0, text.length()) <= TOOL_RESULT_CHARACTERS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, TOOL_RESULT_CHARACTERS)) + "\n（工具正文已按上下文预算截断）";
    }
}
