package cn.ray.agent.server.rag.parse;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.List;

/** 只接受严格 UTF-8；统一换行，保留 Markdown 标题上下文，不执行 HTML/脚本。 */
public final class TextDocumentParser implements DocumentParser {
    private final boolean markdown;

    public TextDocumentParser(boolean markdown) {
        this.markdown = markdown;
    }

    @Override
    public String version() {
        return markdown ? "markdown-utf8-v1" : "text-utf8-v1";
    }

    @Override
    public List<ParsedSection> parse(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            if (text.length() > 200000 || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
                throw new Failure("文本过长或包含二进制控制字符");
            }
            List<ParsedSection> sections = new ArrayList<>();
            String heading = "";
            boolean fenced = false;
            StringBuilder paragraph = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                boolean fence = markdown && (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~"));
                if (markdown && !fenced && line.matches("#{1,6}\\s+.+")) {
                    flush(sections, paragraph, heading);
                    heading = line.replaceFirst("^#{1,6}\\s+", "").strip();
                    if (heading.codePointCount(0, heading.length()) > 300) {
                        heading = heading.substring(0, heading.offsetByCodePoints(0, 300));
                    }
                    paragraph.append(line).append('\n');
                } else if (line.isBlank() && !fenced) {
                    flush(sections, paragraph, heading);
                } else {
                    paragraph.append(line).append('\n');
                }
                if (fence) {
                    fenced = !fenced;
                }
            }
            flush(sections, paragraph, heading);
            if (sections.isEmpty()) {
                throw new Failure("文档没有可解析的文本");
            }
            return List.copyOf(sections);
        } catch (CharacterCodingException e) {
            throw new Failure("文本必须使用 UTF-8 编码，请转换后重新上传");
        }
    }

    private void flush(List<ParsedSection> sections, StringBuilder text, String heading) {
        String content = text.toString().strip();
        if (!content.isBlank()) {
            sections.add(new ParsedSection(content, null, sections.size() + 1, heading));
        }
        text.setLength(0);
    }
}
