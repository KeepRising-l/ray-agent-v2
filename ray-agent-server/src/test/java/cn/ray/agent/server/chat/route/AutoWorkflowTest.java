package cn.ray.agent.server.chat.route;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.tool.*;
import cn.ray.agent.server.chat.*;
import cn.ray.agent.server.rag.answer.*;
import cn.ray.agent.server.rag.retrieve.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoWorkflowTest {
    private final RoutePolicy policy = new RoutePolicy();
    private final IntentRouter router = new IntentRouter(policy);
    private final GroundedAnswerService answers = mock(GroundedAnswerService.class);
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolExecutor tools = new ToolExecutor(registry);
    private final ReactLoopAgent agent = new ReactLoopAgent(registry, tools);
    private final WorkflowRunner runner = new WorkflowRunner();
    private final KnowledgeChatWorkflow knowledge = new KnowledgeChatWorkflow(answers);
    private final GeneralChatWorkflow general = new GeneralChatWorkflow(agent);
    private final HybridChatWorkflow hybrid = new HybridChatWorkflow(answers, tools, new SourceAttributionPolicy());
    private final AutoChatWorkflow auto = new AutoChatWorkflow(runner, router, new RouteTraceRecorder(), general, knowledge, hybrid);
    private final ChatWorkflow.Scope scope = new ChatWorkflow.Scope("owner", List.of("kb"));
    private final EvidenceAssembler.Evidence evidence = new EvidenceAssembler().assemble(List.of(new RetrievedChunk(
            "chunk", "kb", "doc", "项目资料", "version", 1, "部署代号是 Pine42。", 1, 1, "部署", 0, 15, .9)));
    private final List<RagStreamEventMapper.Event> events = new CopyOnWriteArrayList<>();
    private final List<AgentEventSink.Event> toolEvents = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final ChatWorkflow.Events sinks = new ChatWorkflow.Events(toolEvents::add, events::add);
    private final RunBudget budget = new RunBudget(5, 2, 3000, 500, 24000);

    @AfterEach
    void close() { runner.close(); knowledge.close(); agent.close(); tools.close(); }

    private GenerateRequest input(String question) {
        return new GenerateRequest(List.of(new Message(Message.Role.USER, question)));
    }

    private LlmAdapter model(String... outputs) {
        AtomicInteger count = new AtomicInteger();
        return request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            boolean sent;
            public void request(long n) {
                if (sent) return;
                sent = true;
                try {
                    String output = outputs[count.getAndIncrement()];
                    subscriber.onNext(new StreamChunk.TextDelta(output));
                    subscriber.onNext(new StreamChunk.Finished(StreamChunk.FinishReason.STOP, new TokenUsage(3, 2)));
                    subscriber.onComplete();
                } catch (Throwable error) { subscriber.onError(error); }
            }
            public void cancel() { }
        });
    }

    private Probe ask(LlmAdapter model, String question, ChatWorkflow.Scope selected, RunBudget limits) throws Exception {
        Probe probe = new Probe();
        auto.bind(model, selected, limits, sinks).generate(input(question)).subscribe(probe);
        probe.done.get(5, TimeUnit.SECONDS);
        return probe;
    }

    private String target() {
        return events.stream().filter(event -> event.type().equals("ROUTE_DECIDED")).findFirst().orElseThrow().data().get("target").toString();
    }

    @Test
    void clearRulesAndMissingScopeAreBounded() {
        assertEquals(RouteDecision.Target.DIRECT, policy.rule("你好！", true).orElseThrow().target());
        assertEquals(RouteDecision.Target.KNOWLEDGE, policy.rule("我的资料说的代号？", true).orElseThrow().target());
        assertEquals(RouteDecision.Target.HYBRID, policy.rule("我的资料和网上最新方案对比", true).orElseThrow().target());
        assertEquals(RouteDecision.Target.WEB, policy.rule("搜索最新Java版本", false).orElseThrow().target());
        assertEquals(RouteDecision.Target.CLARIFY, policy.rule("我的资料和网上对比", false).orElseThrow().target());
        assertTrue(policy.rule("它怎么运行？", true).isEmpty());
        assertEquals(RouteDecision.Target.KNOWLEDGE, policy.rule("我的资料是啥，不要联网搜索最新消息", true).orElseThrow().target());
        assertEquals(RouteDecision.Target.WEB, policy.rule("不要读取知识库，请搜索网页", true).orElseThrow().target());
        assertEquals(RouteDecision.Target.DIRECT, policy.rule("不要联网，解释Java", true).orElseThrow().target());
        assertEquals(RouteDecision.Target.DIRECT, policy.rule("直接解释Java接口，只用常识。", true).orElseThrow().target());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> ChatMode.parse("INVALID"));
    }

    @Test
    void classifierRejectsUnknownDuplicateExtraAndTrailingFields() {
        assertEquals(RouteDecision.Target.DIRECT, router.parse("{\"target\":\"DIRECT\"}", true).target());
        assertEquals(RouteDecision.Target.CLARIFY, router.parse("{\"target\":\"KNOWLEDGE\"}", false).target());
        for (String json : List.of("{}", "{\"target\":\"SHELL\"}", "{\"target\":\"WEB\",\"reason\":\"x\"}",
                "{\"target\":\"WEB\",\"target\":\"DIRECT\"}", "{\"target\":\"DIRECT\"} {}")) {
            assertThrows(GroundedAnswerService.Failure.class, () -> router.parse(json, true));
        }
    }

    @Test
    void greetingDoesNotClassifyRetrieveOrAdvertiseTools() throws Exception {
        var probe = ask(request -> {
            assertTrue(request.tools().isEmpty());
            return model("你好").generate(request);
        }, "你好", scope, budget);
        assertNull(probe.error);
        assertEquals("DIRECT", target());
        assertEquals("你好", probe.text.toString());
        verifyNoInteractions(answers);
        assertEquals(0, toolCalls.get());
    }

    @Test
    void modelClassificationIsCountedWithFinalAnswerUsage() throws Exception {
        var probe = ask(model("{\"target\":\"DIRECT\"}", "Java是一门语言。"), "解释Java", scope, budget);
        assertNull(probe.error);
        assertEquals("DIRECT", target());
        assertEquals(6, probe.finished.usage().inputTokens());
        assertEquals(4, probe.finished.usage().outputTokens());
        assertEquals(2, events.stream().filter(event -> event.type().equals("ROUTE_CONTEXT_SELECTED")).count());
    }

    @Test
    void malformedOrFailedClassifierClarifiesWithoutSideEffects() throws Exception {
        for (LlmAdapter model : List.of(model("not json"), (LlmAdapter) request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { subscriber.onError(new IllegalStateException("private upstream error")); }
            public void cancel() { }
        }))) {
            events.clear();
            var probe = ask(model, "它呢？", scope, budget);
            assertInstanceOf(UserQuestionRequired.class, probe.error);
            assertEquals("CLARIFY", target());
            assertTrue(((UserQuestionRequired) probe.error).continuation().question().contains("请明确"));
        }
        verifyNoInteractions(answers);
        assertTrue(toolEvents.stream().noneMatch(event -> event.type().startsWith("TOOL_")));
    }

    @Test
    void missingScopeAsksToSelectRatherThanSearchingInstead() throws Exception {
        var probe = ask(model(), "我的资料是什么？", new ChatWorkflow.Scope("owner", List.of()), budget);
        assertNull(probe.error);
        assertEquals("CLARIFY", target());
        assertTrue(probe.text.toString().contains("选择知识库"));
        verifyNoInteractions(answers);
    }

    @Test
    void knowledgeTargetUsesExistingTwoStageWorkflowWithoutWeb() throws Exception {
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        when(answers.decisionRequest(anyString(), any())).thenReturn(input("judge"));
        when(answers.answerRequest(any(), any())).thenReturn(input("answer"));
        var probe = ask(model("{\"answerable\":true,\"sources\":[{\"id\":\"K1\",\"quote\":\"Pine42\"}]}", "Pine42 [K1]"), "我的资料中的部署代号", scope, budget);
        assertNull(probe.error);
        assertEquals("KNOWLEDGE", target());
        assertTrue(probe.text.toString().contains("Pine42"));
        assertTrue(toolEvents.stream().noneMatch(event -> event.type().startsWith("TOOL_")));
        assertEquals(6, probe.finished.usage().inputTokens());
    }

    private void web(boolean success) {
        for (String name : List.of("web_search", "web_fetch")) {
            registry.register(new Tool() {
                public ToolDefinition definition() {
                    return new ToolDefinition(name, "test", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":300},\"url\":{\"type\":\"string\",\"maxLength\":2000}},\"required\":[],\"additionalProperties\":false}");
                }
                public ToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments, ToolContext context) {
                    toolCalls.incrementAndGet();
                    return success ? new ToolResult(true, "OK", "公开资料部署代号是 Oak73，与本地资料不同。", List.of(new ToolResult.Source("", "公开方案", "https://example.com/guide", "SEARCH")))
                            : ToolResult.failure("TIMEOUT", "搜索超时");
                }
            });
        }
    }

    private String combined(String k, String w, String comparison) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("knowledge", k, "web", w, "comparison", comparison));
    }

    @Test
    void hybridPreservesConflictingSourcesAndEnforcesToolBudget() throws Exception {
        web(true);
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        var probe = ask(model(combined("资料是 Pine42 [K1]", "网页是 Oak73 [W1]", "两类资料冲突，不能自行判断哪个适用 [K1] [W1]")),
                "把我的资料与网上方案对比", scope, budget);
        assertNull(probe.error);
        assertEquals("HYBRID", target());
        assertTrue(probe.text.toString().contains("冲突"));
        assertEquals(2, toolCalls.get());
        assertTrue(events.stream().anyMatch(event -> event.type().equals("HYBRID_VALIDATED")));
        verify(answers).retrieve(anyString(), eq(new RetrievalFilter("owner", List.of("kb"))));
    }

    @Test
    void hybridWebFailureRetainsKnowledgeAndDisclosesMissingWeb() throws Exception {
        web(false);
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        var probe = ask(model(combined("Pine42 [K1]", "", "")), "我的资料与网上比较", scope, budget);
        assertNull(probe.error);
        assertTrue(probe.text.toString().contains("TIMEOUT"));
        assertTrue(probe.text.toString().contains("暂不能完成"));
        assertEquals(1, toolCalls.get());
    }

    @Test
    void hybridMissingKnowledgeDoesNotCallWebKnowledge() throws Exception {
        web(true);
        when(answers.retrieve(anyString(), any())).thenReturn(new EvidenceAssembler.Evidence(List.of(), Map.of()));
        var probe = ask(model(combined("", "Oak73 [W1]", "")), "我的资料与网上比较", scope, budget);
        assertNull(probe.error);
        assertTrue(probe.text.toString().contains("没有知识库事实依据"));
        assertTrue(probe.text.toString().contains("网页资料"));
        assertFalse(probe.text.toString().contains("[K1]"));
    }

    @Test
    void hybridRejectsMixedSectionsAndInventedCitationsBeforePublishingText() throws Exception {
        web(true);
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        var probe = ask(model(combined("Pine42 [W1]", "Oak73 [W1]", "对比[K1][W1]")), "我的资料和网上比较", scope, budget);
        assertInstanceOf(GroundedAnswerService.Failure.class, probe.error);
        assertEquals("", probe.text.toString());
    }

    @Test
    void hybridChecksCurrentVersionAndDoesNotPublishDeletedEvidence() throws Exception {
        web(true);
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        doNothing().doThrow(new GroundedAnswerService.Failure("EVIDENCE_CHANGED", "资料已删除")).when(answers).requireCurrent(anyString(), any());
        var probe = ask(model(combined("Pine42[K1]", "Oak73[W1]", "冲突[K1][W1]")), "我的资料和网上比较", scope, budget);
        assertInstanceOf(GroundedAnswerService.Failure.class, probe.error);
        assertEquals("", probe.text.toString());
    }

    @Test
    void cancellationAndTimeoutReachClassifierSubscription() throws Exception {
        for (boolean cancel : List.of(true, false)) {
            AtomicBoolean stopped = new AtomicBoolean();
            CountDownLatch subscribed = new CountDownLatch(1);
            LlmAdapter slow = request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) { subscribed.countDown(); }
                public void cancel() { stopped.set(true); }
            });
            var limits = new RunBudget(5, 2, cancel ? 2000 : 100, 100, 24000);
            var probe = new Probe();
            auto.bind(slow, scope, limits, sinks).generate(input("它怎么运行？")).subscribe(probe);
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            if (cancel) probe.subscription.cancel();
            else {
                probe.done.get(2, TimeUnit.SECONDS);
                assertInstanceOf(GroundedAnswerService.Failure.class, probe.error);
                assertEquals("TIMEOUT", ((GroundedAnswerService.Failure) probe.error).code());
            }
            for (int i=0; i<100 && !stopped.get(); i++) Thread.sleep(10);
            assertTrue(stopped.get());
        }
    }

    @Test
    void explicitModesNeverInvokeAutoClassifierOrCrossTheirBoundary() throws Exception {
        var forbiddenAuto = mock(AutoChatWorkflow.class);
        var dispatcher = new ModeDispatcher(general, knowledge, forbiddenAuto);
        Probe chat = new Probe();
        dispatcher.bind(ChatMode.CHAT, model("普通解释"), scope, budget, sinks).generate(input("我的资料？")).subscribe(chat);
        chat.done.get(3, TimeUnit.SECONDS);
        assertNull(chat.error);
        verifyNoInteractions(answers);
        when(answers.retrieve(anyString(), any())).thenReturn(new EvidenceAssembler.Evidence(List.of(), Map.of()));
        Probe kb = new Probe();
        dispatcher.bind(ChatMode.KNOWLEDGE, model(), scope, budget, sinks).generate(input("请联网搜索最新内容")).subscribe(kb);
        kb.done.get(3, TimeUnit.SECONDS);
        assertNull(kb.error);
        assertEquals(GroundedAnswerService.REFUSAL, kb.text.toString());
        verifyNoInteractions(forbiddenAuto);
        assertTrue(toolEvents.stream().noneMatch(event -> event.type().startsWith("TOOL_")));
    }

    @Test
    void hybridHonorsZeroAndOneToolBudgets() throws Exception {
        web(true);
        when(answers.retrieve(anyString(), any())).thenReturn(evidence);
        var zero = ask(model(combined("Pine42[K1]", "", "")), "我的资料和网上比较", scope,
                new RunBudget(5, 0, 3000, 500, 24000));
        assertNull(zero.error);
        assertEquals(0, toolCalls.get());
        assertTrue(zero.text.toString().contains("BUDGET_DISABLED"));
        var one = ask(model(combined("Pine42[K1]", "Oak73[W1]", "冲突[K1][W1]")), "我的资料和网上比较", scope,
                new RunBudget(5, 1, 3000, 500, 24000));
        assertNull(one.error);
        assertEquals(1, toolCalls.get());
        assertTrue(toolEvents.stream().noneMatch(event -> event.name().equals("web_fetch")));
    }

    @Test
    void classifierAndAnswerShareModelBudgetRatherThanResettingIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var probe = ask(request -> {
            calls.incrementAndGet();
            return model("{\"target\":\"DIRECT\"}").generate(request);
        }, "解释Java接口", scope, new RunBudget(1, 0, 3000, 500, 24000));
        assertNull(probe.error);
        assertEquals(1, calls.get());
        assertTrue(probe.text.toString().contains("预算不足"));
    }

    @Test
    void webRouteCannotPretendToHaveSearchedWhenModelSkipsTools() throws Exception {
        var probe = ask(model("我已经搜索了最新结果。"), "请搜索网上最新方案", scope, budget);
        assertInstanceOf(GroundedAnswerService.Failure.class, probe.error);
        assertEquals("WEB_NOT_EXECUTED", ((GroundedAnswerService.Failure) probe.error).code());
    }

    @Test
    void routeResumeMakesSupplementTheLatestUserMessageInsteadOfConcatenatingAmbiguity() throws Exception {
        var dispatcher=mock(ModeDispatcher.class);
        when(dispatcher.bind(any(),any(),any(),any(),any())).thenReturn(request -> {
            var messages=request.messages();
            assertEquals("直接解释Java接口，只用常识。",messages.get(messages.size()-1).content());
            assertEquals(Message.Role.ASSISTANT,messages.get(messages.size()-2).role());
            assertTrue(messages.get(0).content().contains("那个方案"));
            return model("Java接口定义行为契约。").generate(request);
        });
        var original=input("那个方案如何，请先问清需求");
        var continuation=new UserQuestionRequired.Continuation("AUTO_ROUTE","你希望做什么？",original,null);
        var snapshot=new cn.ray.agent.server.run.question.PendingQuestionStore.Snapshot(1,continuation,budget,1,10,100,new TokenUsage(3,2),"");
        var accepted=new cn.ray.agent.server.run.question.PendingQuestionStore.Accepted(snapshot,"直接解释Java接口，只用常识。");
        var resume=new cn.ray.agent.server.run.question.RunResumeService(runner,agent,dispatcher);
        var probe=new Probe();resume.bind(model(),accepted,ChatMode.AUTO,scope,sinks).generate(original).subscribe(probe);
        probe.done.get(3,TimeUnit.SECONDS);assertNull(probe.error);assertTrue(probe.text.toString().contains("接口"));
    }

    static class Probe implements Flow.Subscriber<StreamChunk> {
        final StringBuilder text = new StringBuilder();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Flow.Subscription subscription;
        StreamChunk.Finished finished;
        Throwable error;
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(Long.MAX_VALUE); }
        public void onNext(StreamChunk value) {
            if (value instanceof StreamChunk.TextDelta delta) text.append(delta.text());
            if (value instanceof StreamChunk.Finished result) finished=result;
        }
        public void onError(Throwable value) { error=value; done.complete(null); }
        public void onComplete() { done.complete(null); }
    }
}
