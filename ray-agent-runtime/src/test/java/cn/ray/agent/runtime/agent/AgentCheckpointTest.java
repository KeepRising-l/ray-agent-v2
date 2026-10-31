package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentCheckpointTest {
    private LlmAdapter scripted(java.util.function.Function<GenerateRequest,List<StreamChunk>> action) {
        return request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            boolean sent;
            public void request(long n) {
                if (sent) return;
                sent=true;
                try { action.apply(request).forEach(subscriber::onNext); subscriber.onComplete(); }
                catch (Throwable error) { subscriber.onError(error); }
            }
            public void cancel() { }
        });
    }

    private Tool tool(String name, AtomicInteger count) {
        return new Tool() {
            public ToolDefinition definition() { return new ToolDefinition(name,"test","{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"maxLength\":600}},\"required\":[],\"additionalProperties\":false}"); }
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments, ToolContext context) {
                count.incrementAndGet();
                return name.equals("ask_user") ? new ToolResult(true,"WAITING_USER",arguments.toString(),List.of())
                        : new ToolResult(true,"OK","旧观察资料",List.of(new ToolResult.Source("","来源","https://example.com","SEARCH")));
            }
        };
    }

    @Test
    void serializedCheckpointContinuesSameToolSequenceWithoutReplayingSearch() throws Exception {
        var registry=new ToolRegistry();var search=new AtomicInteger();var questions=new AtomicInteger();
        registry.register(tool("web_search",search));registry.register(tool("ask_user",questions));
        try (var tools=new ToolExecutor(registry);var agent=new ReactLoopAgent(registry,tools)) {
            var step=new AtomicInteger();
            var model=scripted(request -> {
                int n=step.incrementAndGet();
                if (n<=2) return List.of(new StreamChunk.ToolCallDelta(0,"call"+n,n==1?"web_search":"ask_user",n==1?"{}":"{\"question\":\"请选择目标城市？\"}"),new StreamChunk.Finished(StreamChunk.FinishReason.TOOL_CALLS,new TokenUsage(2,1)));
                assertEquals(Message.Role.TOOL,request.messages().get(request.messages().size()-1).role());
                assertTrue(request.messages().get(request.messages().size()-1).content().contains("杭州"));
                return List.of(new StreamChunk.TextDelta("杭州，沿用已取得的网页观察[W1]"),new StreamChunk.Finished(StreamChunk.FinishReason.STOP,new TokenUsage(4,2)));
            });
            var request=new GenerateRequest(List.of(new Message(Message.Role.USER,"先搜索，再问城市")));
            var first=new Probe();
            agent.bind(model,Set.of("web_search","ask_user"),new RunBudget(5,4,3000,500,24000),event -> {}).generate(request).subscribe(first);
            first.done.get(3,TimeUnit.SECONDS);
            var waiting=assertInstanceOf(UserQuestionRequired.class,first.error);
            var mapper=new ObjectMapper();
            var cp=mapper.readValue(mapper.writeValueAsString(waiting.continuation().agent()),AgentCheckpoint.class);
            assertEquals(2,cp.modelSteps());assertEquals(2,cp.toolCalls());
            var second=new Probe();agent.resume(model,cp,"杭州",event -> {}).generate(request).subscribe(second);
            second.done.get(3,TimeUnit.SECONDS);
            assertNull(second.error);assertTrue(second.text.toString().contains("杭州"));
            assertEquals(1,search.get());assertEquals(1,questions.get());assertEquals(3,step.get());
        }
    }

    @Test
    void mixedAskBatchFailsBeforeExecutingAnyTool() throws Exception {
        var registry=new ToolRegistry();var count=new AtomicInteger();registry.register(tool("ask_user",count));registry.register(tool("web_search",count));
        try(var tools=new ToolExecutor(registry);var agent=new ReactLoopAgent(registry,tools)) {
            var model=scripted(request -> List.of(new StreamChunk.ToolCallDelta(0,"a","ask_user","{\"question\":\"哪个？\"}"),
                    new StreamChunk.ToolCallDelta(1,"b","web_search","{}"),new StreamChunk.Finished(StreamChunk.FinishReason.TOOL_CALLS,null)));
            var probe=new Probe();agent.bind(model,Set.of("ask_user","web_search"),new RunBudget(5,4,3000,500,24000),event -> {})
                    .generate(new GenerateRequest(List.of(new Message(Message.Role.USER,"test")))).subscribe(probe);
            probe.done.get(3,TimeUnit.SECONDS);assertInstanceOf(ReactLoopAgent.Failure.class,probe.error);assertEquals(0,count.get());
        }
    }

    static class Probe implements Flow.Subscriber<StreamChunk> {
        final CompletableFuture<Void> done=new CompletableFuture<>();final StringBuilder text=new StringBuilder();Throwable error;
        public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        public void onNext(StreamChunk c) { if(c instanceof StreamChunk.TextDelta t)text.append(t.text()); }
        public void onError(Throwable e) {error=e;done.complete(null);}
        public void onComplete(){done.complete(null);}
    }
}
