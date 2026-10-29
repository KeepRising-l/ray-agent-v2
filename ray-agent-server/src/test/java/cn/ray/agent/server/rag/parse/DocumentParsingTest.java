package cn.ray.agent.server.rag.parse;

import cn.ray.agent.server.rag.chunk.*;
import cn.ray.agent.server.knowledge.DocumentFileStore;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentParsingTest {
    @Test
    void utf8BomLineEndingsHeadingsAndCodeFenceArePreserved() {
        String input = "\uFEFF# 标题\r\n\r\n第一段 Pine42。\r\n\r\n```java\r\n# 这是代码\r\n\r\nvalue\r\n```\r\n";
        var sections = new TextDocumentParser(true).parse(input.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, sections.size());
        assertEquals("标题", sections.get(1).heading());
        assertEquals(2, sections.get(1).paragraph());
        assertTrue(sections.get(2).content().contains("# 这是代码"));
        assertEquals("标题", sections.get(2).heading());
        assertNull(sections.get(0).page());
    }

    @Test
    void emptyBinaryInvalidEncodingAndExcessiveTextFailExplicitly() {
        var parser = new TextDocumentParser(false);
        for (byte[] input : List.of(new byte[]{(byte) 0xff}, new byte[]{0, 1}, "  \n ".getBytes(), "x".repeat(200001).getBytes())) {
            assertThrows(DocumentParser.Failure.class, () -> parser.parse(input));
        }
    }

    @Test
    void textPdfRetainsPageNumbersAndEmptyEncryptedOrBrokenPdfFails() throws Exception {
        var parser = new PdfDocumentParser();
        var sections = parser.parse(pdf(false, false));
        assertEquals(2, sections.size());
        assertEquals(1, sections.get(0).page());
        assertEquals(2, sections.get(1).page());
        assertTrue(sections.get(1).content().contains("Pine42 page two"));
        assertThrows(DocumentParser.Failure.class, () -> parser.parse(pdf(true, false)));
        assertThrows(DocumentParser.Failure.class, () -> parser.parse(pdf(false, true)));
        assertThrows(DocumentParser.Failure.class, () -> parser.parse("%PDF-broken".getBytes()));
        assertThrows(DocumentParser.Failure.class, () -> parser.parse("not-pdf".getBytes()));
    }

    private byte[] pdf(boolean empty, boolean encrypted) throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (String text : List.of("RayAgent page one", "Pine42 page two")) {
                var page = new PDPage();
                doc.addPage(page);
                if (!empty) {
                    try (var content = new PDPageContentStream(doc, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            if (encrypted) {
                doc.protect(new StandardProtectionPolicy("owner", "reader", new AccessPermission()));
            }
            doc.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void chunksAreReproducibleBoundedAndDoNotLoseUnicodeOrSourceLocations() {
        String text = "树木🌲需要浇水。".repeat(200);
        var section = new ParsedSection(text, 2, 3, "植物");
        var chunker = new DocumentChunker();
        var chunks = chunker.chunk("version-a", List.of(section), ChunkPolicy.DEFAULT);
        assertEquals(chunks, chunker.chunk("version-a", List.of(section), ChunkPolicy.DEFAULT));
        assertNotEquals(chunks.get(0).id(), chunker.chunk("version-b", List.of(section), ChunkPolicy.DEFAULT).get(0).id());
        int covered = 0;
        for (var chunk : chunks) {
            assertEquals(2, chunk.page());
            assertEquals(3, chunk.paragraph());
            assertEquals(text.substring(chunk.startOffset(), chunk.endOffset()), chunk.content());
            assertTrue(chunk.startOffset() <= covered);
            assertTrue(chunk.content().codePointCount(0, chunk.content().length()) <= 800);
            assertFalse(Character.isLowSurrogate(chunk.content().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1)));
            covered = chunk.endOffset();
        }
        assertEquals(text.length(), covered);
        assertEquals(1, chunker.chunk("short", List.of(new ParsedSection("短文", null, 1, "")), ChunkPolicy.DEFAULT).size());
    }

    @Test
    void fileKeysCannotTraverseAndReadVerifiesChecksum(@TempDir Path directory) throws Exception {
        var files = new DocumentFileStore(directory.toString());
        byte[] input = "自写测试资料".getBytes(StandardCharsets.UTF_8);
        String key = files.save(input);
        assertArrayEquals(input, files.read(key, DocumentFileStore.sha256(input)));
        assertThrows(java.io.IOException.class, () -> files.read(key, "bad-hash"));
        assertThrows(IllegalArgumentException.class, () -> files.read("../secret", "hash"));
        files.discard(key);
        assertEquals(0, java.nio.file.Files.list(directory).count());
    }
}
