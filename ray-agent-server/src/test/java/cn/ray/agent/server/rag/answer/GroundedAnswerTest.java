package cn.ray.agent.server.rag.answer;

import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.chat.KnowledgeChatWorkflow;
import cn.ray.agent.server.rag.retrieve.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroundedAnswerTest {
    private final RetrievedChunk hit = new RetrievedChunk("chunk", "kb", "doc", "自写资料", "version", 1,
            "果园的测试代号是 Pine42。", 2, 1, "果园", 0, 22, .8);
    private final EvidenceAssembler.Evidence evidence = new EvidenceAssembler().assemble(List.of(hit));
    private final RetrievalFilter scope = new RetrievalFilter("owner", List.of("kb"));
    private final GenerateRequest request = new GenerateRequest(List.of(new Message(Message.Role.USER, "代号是什么？")));

    @Test
    void selectionRequiresKnownSourceAndActualNonblankQuote() {
        var validator = new CitationValidator();
        assertTrue(validator.decision("{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"Pine42\"}]}", evidence).answerable());
        assertFalse(validator.decision("{\"answerable\":false,\"sources\":[]}", evidence).answerable());
        for (String invalid : List.of("{\"answerable\":true,\"sources\":[]}",
                "{\"answerable\":true,\"sources\":[{\"id\":\"K99\",\"quote\":\"Pine42\"}]}",
                "{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"Oak99\"}]}",
                "{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"   \"}]}",
                "{\"answerable\":false,\"sources\":[]} {}")) {
            assertThrows(GroundedAnswerService.Failure.class, () -> validator.decision(invalid, evidence));
        }
        assertEquals(Set.of("K1"), validator.validateAnswer("代号 Pine42 [K1]", evidence));
        assertThrows(GroundedAnswerService.Failure.class, () -> validator.validateAnswer("Pine42 [K999]", evidence));
        assertThrows(GroundedAnswerService.Failure.class, () -> validator.validateAnswer("Pine42 [W1]", evidence));
        assertThrows(GroundedAnswerService.Failure.class, () -> validator.validateAnswer("没有引用", evidence));
    }

    @Test
    void evidenceIsBoundedDeduplicatedAndKeepsPageAndVersion() {
        var assembled = new EvidenceAssembler().assemble(List.of(hit, hit));
        assertEquals(1, assembled.citations().size());
        assertEquals(2, assembled.citations().get(0).page());
        assertEquals("version", assembled.citations().get(0).versionId());
        assertTrue(assembled.prompt().contains("Pine42"));
        assertTrue(assembled.select(Set.of()).citations().isEmpty());
    }

    @Test
    void evidenceJudgeAlsoChecksCombinedInputBudget() {
        var service = new GroundedAnswerService(mock(RetrievalService.class), mock(cn.ray.agent.server.knowledge.DocumentRepository.class),
                mock(cn.ray.agent.server.conversation.ConversationRepository.class));
        var grounded = service.answerRequest(request, evidence);
        assertFalse(grounded.messages().stream().filter(message -> message.role() == Message.Role.SYSTEM)
                .anyMatch(message -> message.content().contains("Pine42")));
        assertTrue(grounded.messages().get(grounded.messages().size() - 1).content().contains("Pine42"));
        var large = new EvidenceAssembler().assemble(List.of(new RetrievedChunk("chunk", "kb", "doc", "大资料", "version", 1,
                "汉".repeat(4500), null, 1, "", 0, 4500, .5)));
        var failure = assertThrows(GroundedAnswerService.Failure.class, () -> service.decisionRequest("问".repeat(4000), large));
        assertEquals("CONTEXT_BUDGET", failure.code());
    }

    private LlmAdapter model(java.util.function.IntFunction<List<StreamChunk>> results, AtomicInteger calls) {
        return input -> subscriber -> {
            assertTrue(input.tools().isEmpty(), "KNOWLEDGE must not advertise tools");
            subscriber.onSubscribe(new Flow.Subscription() {
                boolean sent;
                public void request(long n) {
                    if (sent) return;
                    sent = true;
                    try {
                        results.apply(calls.incrementAndGet()).forEach(subscriber::onNext);
                        subscriber.onComplete();
                    } catch (Throwable e) { subscriber.onError(e); }
                }
                public void cancel() { }
            });
        };
    }

    private List<StreamChunk> text(String text) {
        return List.of(new StreamChunk.TextDelta(text), new StreamChunk.Finished(StreamChunk.FinishReason.STOP, null));
    }

    private GroundedAnswerService answers() {
        var answers = mock(GroundedAnswerService.class);
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        when(answers.decisionRequest(anyString(), any())).thenReturn(request);
        when(answers.answerRequest(any(), any())).thenReturn(request);
        return answers;
    }

    @Test
    void supportedAnswerUsesTwoFixedModelCallsAndPersistsOnlyMetadataEvents() throws Exception {
        var answers = answers();
        var workflow = new KnowledgeChatWorkflow(answers);
        var events = new CopyOnWriteArrayList<RagStreamEventMapper.Event>();
        var calls = new AtomicInteger();
        try {
            var model = model(step -> text(step == 1 ? "{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"Pine42\"}]}" : "代号是 Pine42 [K1]。"), calls);
            Probe probe = new Probe();
            workflow.bind(model, scope, 2000, events::add).generate(request).subscribe(probe);
            probe.done.get(3, TimeUnit.SECONDS);
            assertNull(probe.error);
            assertEquals(2, calls.get());
            assertEquals("代号是 Pine42 [K1]。", probe.text.toString());
            assertTrue(events.stream().anyMatch(event -> event.type().equals("RAG_VALIDATED")));
            assertFalse(events.stream().anyMatch(event -> event.data().toString().contains("Pine42")));
            verify(answers, times(2)).requireCurrent(anyString(), any());
        } finally { workflow.close(); }
    }

    @Test
    void noHitsAndIrrelevantEvidenceProduceFixedRefusalWithoutAnswerGeneration() throws Exception {
        for (boolean empty : List.of(true, false)) {
            var answers = answers();
            if (empty) when(answers.retrieve(anyString(), any())).thenReturn(new EvidenceAssembler().assemble(List.of()));
            var calls = new AtomicInteger();
            var workflow = new KnowledgeChatWorkflow(answers);
            try {
                Probe probe = new Probe();
                workflow.bind(model(step -> text("{\"answerable\":false,\"sources\":[]}"), calls), scope, 2000, event -> { })
                        .generate(request).subscribe(probe);
                probe.done.get(3, TimeUnit.SECONDS);
                assertNull(probe.error);
                assertEquals(GroundedAnswerService.REFUSAL, probe.text.toString());
                assertEquals(empty ? 0 : 1, calls.get());
                verify(answers, never()).answerRequest(any(), any());
            } finally { workflow.close(); }
        }
    }

    @Test
    void inventedCitationAndModelToolCallsFailInsteadOfBecomingKnowledgeAnswer() throws Exception {
        for (boolean tool : List.of(false, true)) {
            var workflow = new KnowledgeChatWorkflow(answers());
            try {
                var calls = new AtomicInteger();
                var model = model(step -> tool ? List.of(new StreamChunk.ToolCallDelta(0, "a", "web_search", "{}"))
                        : text(step == 1 ? "{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"Pine42\"}]}" : "答案 [K999]"), calls);
                Probe probe = new Probe();
                workflow.bind(model, scope, 2000, event -> { }).generate(request).subscribe(probe);
                probe.done.get(3, TimeUnit.SECONDS);
                assertInstanceOf(GroundedAnswerService.Failure.class, probe.error);
                assertEquals(tool ? "TOOL_FORBIDDEN" : "INVALID_CITATION", ((GroundedAnswerService.Failure) probe.error).code());
            } finally { workflow.close(); }
        }
    }

    @Test
    void sourcesChangedDuringGenerationFailBeforeSuccessAndIdleModelTimesOut() throws Exception {
        var changed = answers();
        doNothing().doThrow(new GroundedAnswerService.Failure("EVIDENCE_CHANGED", "资料已更新")).when(changed).requireCurrent(anyString(), any());
        var workflow = new KnowledgeChatWorkflow(changed);
        try {
            Probe probe = new Probe();
            workflow.bind(model(step -> text(step == 1 ? "{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"Pine42\"}]}" : "Pine42 [K1]"), new AtomicInteger()), scope, 2000, event -> { })
                    .generate(request).subscribe(probe);
            probe.done.get(3, TimeUnit.SECONDS);
            assertEquals("EVIDENCE_CHANGED", ((GroundedAnswerService.Failure) probe.error).code());
        } finally { workflow.close(); }
        var idle = new KnowledgeChatWorkflow(answers());
        try {
            LlmAdapter model = request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) { }
                public void cancel() { }
            });
            Probe probe = new Probe();
            idle.bind(model, scope, 100, event -> { }).generate(request).subscribe(probe);
            probe.done.get(2, TimeUnit.SECONDS);
            assertEquals("TIMEOUT", ((GroundedAnswerService.Failure) probe.error).code());
        } finally { idle.close(); }
    }

    @Test
    void cancellationInterruptsRetrievalAndDoesNotStartModel() throws Exception {
        var answers = answers();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(answers.retrieve(anyString(), any())).thenAnswer(call -> {
            started.countDown();
            try { Thread.sleep(5000); } finally { interrupted.countDown(); }
            return evidence;
        });
        var workflow = new KnowledgeChatWorkflow(answers);
        try {
            var calls = new AtomicInteger();
            var handle = new java.util.concurrent.atomic.AtomicReference<Flow.Subscription>();
            workflow.bind(model(step -> text("unreachable"), calls), scope, 3000, event -> { }).generate(request).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription value) { handle.set(value); value.request(1); }
                public void onNext(StreamChunk value) { fail("event after cancellation"); }
                public void onError(Throwable error) { fail("error after cancellation"); }
                public void onComplete() { fail("completion after cancellation"); }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            handle.get().cancel();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        } finally { workflow.close(); }
    }

    private static final class Probe implements Flow.Subscriber<StreamChunk> {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private volatile Throwable error;
        public void onSubscribe(Flow.Subscription value) { value.request(Long.MAX_VALUE); }
        public void onNext(StreamChunk value) { if (value instanceof StreamChunk.TextDelta delta) text.append(delta.text()); }
        public void onError(Throwable e) { error = e; done.complete(null); }
        public void onComplete() { done.complete(null); }
    }
}
