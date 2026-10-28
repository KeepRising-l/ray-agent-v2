package cn.ray.agent.runtime.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleAdapterTest {
    private static final String TEXT = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n";
    private static final String FINISH = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    private static final String DONE = "data: [DONE]\n\n";

    private static final class Collect implements Flow.Subscriber<StreamChunk> {
        final List<StreamChunk> events = new ArrayList<>();
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile Throwable failure;
        volatile boolean completed;
        volatile Flow.Subscription subscription;
        public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(Long.MAX_VALUE); }
        public void onNext(StreamChunk value) { events.add(value); }
        public void onError(Throwable error) { failure = error; terminal.countDown(); }
        public void onComplete() { completed = true; terminal.countDown(); }
        void await() throws InterruptedException { assertTrue(terminal.await(8, TimeUnit.SECONDS), "stream did not terminate"); }
    }
    private static GenerateRequest request() { return new GenerateRequest(List.of(new Message(Message.Role.USER, "Hi"))); }
    private static OpenAiCompatibleAdapter adapter(int port) {
        return new OpenAiCompatibleAdapter(URI.create("http://127.0.0.1:" + port + "/chat/completions"), "local-test-key", "test-model", new RetryPolicy(2, Duration.ofMillis(10)));
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (code == 200) exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    @Test void normalStreamingAndPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> payload = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, TEXT + FINISH + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n" + DONE);
        });
        server.start();
        try (var a = adapter(server.getAddress().getPort())) {
            var subscriber = new Collect(); a.generate(request()).subscribe(subscriber); subscriber.await();
            assertNull(subscriber.failure); assertTrue(subscriber.completed); assertEquals(new StreamChunk.TextDelta("Hello"), subscriber.events.get(0));
            var finished = (StreamChunk.Finished) subscriber.events.get(1);
            assertEquals(StreamChunk.FinishReason.STOP, finished.reason()); assertEquals(new TokenUsage(5, 2), finished.usage());
            assertTrue(payload.get().contains("test-model")); assertTrue(payload.get().contains("\"stream\":true"));
        } finally { server.stop(0); }
    }
    @Test void retriesHttp503BeforeOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger count = new AtomicInteger();
        server.createContext("/chat/completions", ex -> { if (count.incrementAndGet() == 1) respond(ex, 503, "unavailable"); else respond(ex, 200, TEXT + FINISH + DONE); }); server.start();
        try (var a = adapter(server.getAddress().getPort())) { var s = new Collect(); a.generate(request()).subscribe(s); s.await(); assertNull(s.failure); assertEquals(2, count.get()); }
        finally { server.stop(0); }
    }
    @Test void partialOutputIsNotRetriedAfterDisconnect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger count = new AtomicInteger();
        server.createContext("/chat/completions", ex -> { count.incrementAndGet(); respond(ex, 200, TEXT); }); server.start();
        try (var a = adapter(server.getAddress().getPort())) { var s = new Collect(); a.generate(request()).subscribe(s); s.await(); assertNotNull(s.failure); assertFalse(s.completed); assertEquals(1, s.events.size()); assertEquals(1, count.get()); }
        finally { server.stop(0); }
    }
    @Test void unreachableConnectionFails() throws Exception {
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var a = adapter(port)) { var s = new Collect(); a.generate(request()).subscribe(s); s.await(); assertNotNull(s.failure); assertTrue(s.events.isEmpty()); }
    }
    @Test void cancellationStopsAfterFirstEvent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> respond(ex, 200, TEXT + TEXT + FINISH + DONE)); server.start();
        CountDownLatch first = new CountDownLatch(1); AtomicInteger count = new AtomicInteger();
        try (var a = adapter(server.getAddress().getPort())) {
            a.generate(request()).subscribe(new Flow.Subscriber<>() {
                Flow.Subscription subscription;
                public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(Long.MAX_VALUE); }
                public void onNext(StreamChunk item) { count.incrementAndGet(); subscription.cancel(); first.countDown(); }
                public void onError(Throwable t) { first.countDown(); }
                public void onComplete() { first.countDown(); }
            });
            assertTrue(first.await(5, TimeUnit.SECONDS)); assertEquals(1, count.get());
        } finally { server.stop(0); }
    }
    @Test void translatesToolDefinitionsAndStreamingToolArguments() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> payload = new AtomicReference<>();
        String stream = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Java\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" + DONE;
        server.createContext("/chat/completions", ex -> { payload.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); respond(ex, 200, stream); }); server.start();
        try (var a = adapter(server.getAddress().getPort())) {
            var req = new GenerateRequest(List.of(new Message(Message.Role.USER, "Search")), new GenerateOptions(null, null, null), List.of(new ToolDefinition("search", "Search web", "{\"type\":\"object\"}")));
            var sub = new Collect(); a.generate(req).subscribe(sub); sub.await(); assertNull(sub.failure);
            var first = (StreamChunk.ToolCallDelta) sub.events.get(0); var second = (StreamChunk.ToolCallDelta) sub.events.get(1);
            assertEquals("call-1", first.id()); assertEquals("search", first.name()); assertEquals("{\"q\":\"Java\"}", first.argumentsFragment()+second.argumentsFragment());
            assertEquals(StreamChunk.FinishReason.TOOL_CALLS, ((StreamChunk.Finished)sub.events.get(2)).reason());
            assertTrue(payload.get().contains("\"tools\"")); assertTrue(payload.get().contains("\"parameters\":{"));
        } finally { server.stop(0); }
    }
    @Test void badRequestIsNotRetried() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger count = new AtomicInteger();
        server.createContext("/chat/completions", ex -> { count.incrementAndGet(); respond(ex, 400, "invalid request"); }); server.start();
        try (var a = adapter(server.getAddress().getPort())) { var sub = new Collect(); a.generate(request()).subscribe(sub); sub.await(); assertNotNull(sub.failure); assertEquals(1, count.get()); }
        finally { server.stop(0); }
    }
}
