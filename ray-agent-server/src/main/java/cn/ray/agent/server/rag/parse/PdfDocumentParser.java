package cn.ray.agent.server.rag.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import java.util.ArrayList;
import java.util.List;

/** 使用 PDFBox 逐页提取文本；页码保留，扫描/OCR、复杂版面语义理解不在本节范围。 */
public final class PdfDocumentParser implements DocumentParser {
    @Override
    public String version() {
        return "pdfbox-3.0.8-pages-v1";
    }

    @Override
    public List<ParsedSection> parse(byte[] bytes) {
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new Failure("文件内容不是有效的 PDF");
        }
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) {
                throw new Failure("暂不支持加密或禁止提取文本的 PDF");
            }
            if (document.getNumberOfPages() > 100) {
                throw new Failure("PDF 不能超过 100 页");
            }
            List<ParsedSection> sections = new ArrayList<>();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int total = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).replace("\r\n", "\n").replace('\r', '\n').strip();
                total += text.length();
                if (total > 200000) {
                    throw new Failure("PDF 提取文本超过 20 万字符");
                }
                int paragraph = 0;
                for (String part : text.split("\\n\\s*\\n")) {
                    if (!part.isBlank()) {
                        sections.add(new ParsedSection(part.strip(), page, ++paragraph, ""));
                    }
                }
            }
            if (sections.isEmpty()) {
                throw new Failure("PDF 没有可提取文本，可能是扫描件；当前尚未接入 OCR");
            }
            return List.copyOf(sections);
        } catch (InvalidPasswordException e) {
            throw new Failure("PDF 需要密码，当前暂不支持");
        } catch (java.io.IOException e) {
            throw new Failure("PDF 解析失败，请检查文件是否损坏");
        }
    }
}
