package cn.ray.agent.server.rag.chunk;

import cn.ray.agent.server.rag.parse.ParsedSection;
import cn.ray.agent.server.rag.parse.DocumentParser;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 段落不跨页合并；长段优先在后半段标点/空白处切开，保证不切断代理对。 */
public final class DocumentChunker {
    public List<DocumentChunk> chunk(String versionId, List<ParsedSection> sections, ChunkPolicy policy) {
        List<DocumentChunk> result = new ArrayList<>();
        for (ParsedSection section : sections) {
            String text = section.content();
            int start = 0;
            while (start < text.length()) {
                int count = text.codePointCount(start, text.length());
                int end = text.offsetByCodePoints(start, Math.min(policy.maxCharacters(), count));
                if (end < text.length()) {
                    int lower = text.offsetByCodePoints(start, policy.maxCharacters() / 2);
                    for (int candidate = end; candidate > lower;) {
                        int codePoint = text.codePointBefore(candidate);
                        if (Character.isWhitespace(codePoint) || "。！？；.!?;".indexOf(codePoint) >= 0) {
                            end = candidate;
                            break;
                        }
                        candidate -= Character.charCount(codePoint);
                    }
                }
                String content = text.substring(start, end);
                if (!content.isBlank()) {
                    String location = versionId + ":" + policy.id() + ":" + result.size() + ":" + section.page()
                            + ":" + section.paragraph() + ":" + start + ":" + end;
                    String id = UUID.nameUUIDFromBytes(location.getBytes(StandardCharsets.UTF_8)).toString();
                    result.add(new DocumentChunk(id, versionId, result.size(), content, section.page(),
                            section.paragraph(), section.heading(), start, end));
                }
                if (result.size() > 1000) {
                    throw new DocumentParser.Failure("文档片段超过 1000 个，请拆小后上传");
                }
                if (end == text.length()) {
                    break;
                }
                int overlap = Math.min(policy.overlapCharacters(), text.codePointCount(start, end) - 1);
                start = text.offsetByCodePoints(end, -overlap);
            }
        }
        if (result.isEmpty()) {
            throw new DocumentParser.Failure("文档没有可用片段");
        }
        return List.copyOf(result);
    }
}
