package cn.ray.agent.server.rag.parse;

import org.springframework.stereotype.Component;

@Component
public final class ParserSelector {
    public DocumentParser select(String type) {
        return switch (type) {
            case "txt" -> new TextDocumentParser(false);
            case "md" -> new TextDocumentParser(true);
            case "pdf" -> new PdfDocumentParser();
            default -> throw new DocumentParser.Failure("只支持 TXT、Markdown 和文本 PDF");
        };
    }
}
