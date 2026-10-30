package cn.ray.agent.server.rag.answer;

import cn.ray.agent.server.rag.retrieve.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 有限证据集只存在于本次生成，K 编号对应确切版本/片段。分数不决定是否能回答。 */
public final class EvidenceAssembler {
    public Evidence assemble(List<RetrievedChunk> hits) {
        var citations = new ArrayList<Citation>();
        var texts = new LinkedHashMap<String, String>();
        var seen = new HashSet<String>();
        int characters = 0;
        for (var hit : hits) {
            if (!seen.add(hit.chunkId()) || hit.content().isBlank() || characters + hit.content().length() > 4500) {
                continue;
            }
            String id = "K" + (citations.size() + 1);
            citations.add(new Citation(id, hit.kbId(), hit.documentId(), hit.documentName(), hit.versionId(), hit.version(),
                    hit.chunkId(), hit.page(), hit.paragraph(), hit.heading(), hit.startOffset(), hit.endOffset(), hit.score()));
            texts.put(id, hit.content());
            characters += hit.content().length();
        }
        return new Evidence(citations, texts);
    }

    public record Evidence(List<Citation> citations, Map<String, String> texts) {
        public Evidence {
            citations = List.copyOf(citations);
            texts = Map.copyOf(texts);
        }

        public String prompt() {
            try {
                var sources = citations.stream().map(citation -> Map.of("source", citation, "text", texts.get(citation.id()))).toList();
                return new ObjectMapper().writeValueAsString(Map.of("untrustedEvidence", sources));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot serialize evidence");
            }
        }

        public Evidence select(Set<String> ids) {
            var selected = citations.stream().filter(citation -> ids.contains(citation.id())).toList();
            var selectedTexts = new LinkedHashMap<String, String>();
            selected.forEach(citation -> selectedTexts.put(citation.id(), texts.get(citation.id())));
            return new Evidence(selected, selectedTexts);
        }
    }
}
