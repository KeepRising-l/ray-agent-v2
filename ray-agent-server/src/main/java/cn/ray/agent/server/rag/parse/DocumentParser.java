package cn.ray.agent.server.rag.parse;

import java.util.List;

public interface DocumentParser {
    String version();

    List<ParsedSection> parse(byte[] bytes);

    /** 只有经过归类的解析错误可展示给页面，不直接输出第三方异常详情。 */
    final class Failure extends RuntimeException {
        public Failure(String message) {
            super(message);
        }
    }
}
