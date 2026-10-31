package cn.ray.agent.server.rag.answer;

import cn.ray.agent.runtime.context.TokenEstimator;
import cn.ray.agent.runtime.model.Message;
import cn.ray.agent.server.rag.retrieve.*;
import cn.ray.agent.server.knowledge.DocumentRepository;
import cn.ray.agent.server.conversation.ConversationRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EvidenceBudgetTest {
    @Test void longQuestionAndSeveralChunksFitWithoutCuttingOriginalChunkText() {
        String question="问".repeat(4000);
        var hits=new ArrayList<RetrievedChunk>();
        for(int i=0;i<5;i++)hits.add(new RetrievedChunk("c"+i,"kb","d"+i,"资料","v"+i,1,"原".repeat(800),null,1,"",0,800,.8));
        var all=new EvidenceAssembler().assemble(hits);
        var fitted=new EvidenceBudgetPolicy().fit(question,all,8000);
        assertFalse(fitted.citations().isEmpty());assertTrue(fitted.citations().size()<all.citations().size());
        fitted.texts().values().forEach(text->assertEquals("原".repeat(800),text));
        var service=new GroundedAnswerService(mock(RetrievalService.class),mock(DocumentRepository.class),mock(ConversationRepository.class));
        var request=service.decisionRequest(question,fitted);
        assertTrue(request.messages().get(1).content().contains(question));
        assertTrue(request.messages().stream().mapToInt(new TokenEstimator()::estimate).sum()<=24000);
    }
}
