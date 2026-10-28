package cn.ray.agent.runtime.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Chat Completions 协议适配器：仅负责模型 HTTP 与事件转换，不执行工具。 */
public final class OpenAiCompatibleAdapter implements LlmAdapter, AutoCloseable {
    private final URI endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final RetryPolicy retryPolicy;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "model-stream");
        thread.setDaemon(true);
        return thread;
    });

    /** @param endpoint 完整的 chat/completions 地址；apiKey 不应写入源码或日志。 */
    public OpenAiCompatibleAdapter(URI endpoint, String apiKey, String defaultModel, RetryPolicy retryPolicy) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) throw new IllegalArgumentException("HTTP(S) endpoint required");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) throw new IllegalArgumentException("invalid endpoint");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        if (apiKey.isBlank() || defaultModel.isBlank()) throw new IllegalArgumentException("apiKey and defaultModel must not be blank");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 单次生成只允许一个订阅者，避免同一个流被重复触发。 */
    @Override public Flow.Publisher<StreamChunk> generate(GenerateRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AtomicBoolean subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber must not be null");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
                subscriber.onError(new IllegalStateException("generation permits one subscriber"));
                return;
            }
            ModelSubscription subscription = new ModelSubscription(subscriber, request);
            subscriber.onSubscribe(subscription);
        };
    }

    private ObjectNode requestBody(GenerateRequest request) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        GenerateOptions options = request.options();
        root.put("model", options.model() == null ? defaultModel : options.model());
        root.put("stream", true);
        root.put("n", 1);
        if (options.temperature() != null) root.put("temperature", options.temperature());
        if (options.maxOutputTokens() != null) root.put("max_tokens", options.maxOutputTokens());
        ArrayNode messages = root.putArray("messages");
        for (Message message : request.messages()) {
            ObjectNode item = messages.addObject();
            item.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
            if (message.content() == null) item.putNull("content"); else item.put("content", message.content());
            if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = item.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode c = calls.addObject(); c.put("id", call.id()); c.put("type", "function");
                    ObjectNode f = c.putObject("function"); f.put("name", call.name()); f.put("arguments", call.argumentsJson());
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode item = tools.addObject(); item.put("type", "function");
                ObjectNode fn = item.putObject("function"); fn.put("name", tool.name()); fn.put("description", tool.description());
                var schema = mapper.readTree(tool.parametersSchemaJson());
                if (schema == null || !schema.isObject()) throw new IOException("tool schema must be a JSON object");
                fn.set("parameters", schema);
            }
        }
        return root;
    }

    private final class ModelSubscription implements Flow.Subscription, Runnable {
        private final Flow.Subscriber<? super StreamChunk> subscriber;
        private final GenerateRequest request;
        private final Object lock = new Object();
        private long demand;
        private boolean started;
        private volatile boolean terminated;
        private volatile boolean cancelled;
        private volatile InputStream body;
        private volatile Thread worker;
        private volatile Throwable protocolError;
        private boolean emitted;

        private ModelSubscription(Flow.Subscriber<? super StreamChunk> subscriber, GenerateRequest request) { this.subscriber = subscriber; this.request = request; }
        @Override public void request(long n) {
            synchronized (lock) {
                if (cancelled || terminated) return;
                if (n <= 0) { protocolError = new IllegalArgumentException("demand must be positive"); cancelled = true; }
                else demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
                if (!started) {
                    started = true;
                    try { executor.execute(this); }
                    catch (RejectedExecutionException e) { cancelled = true; subscriber.onError(e); }
                }
                lock.notifyAll();
            }
            if (protocolError != null) closeBody();
        }
        @Override public void cancel() {
            cancelled = true;
            synchronized (lock) { lock.notifyAll(); }
            closeBody();
            Thread t = worker;
            if (t != null) t.interrupt();
        }
        private void closeBody() { try { if (body != null) body.close(); } catch (IOException ignored) { } }
        private boolean emit(StreamChunk event) throws InterruptedException {
            synchronized (lock) {
                while (demand == 0 && !cancelled) lock.wait();
                if (cancelled) return false;
                demand--;
            }
            emitted = true;
            subscriber.onNext(event);
            return !cancelled;
        }
        @Override public void run() {
            worker = Thread.currentThread();
            try {
                if (protocolError != null) { subscriber.onError(protocolError); return; }
                String json = mapper.writeValueAsString(requestBody(request));
                for (int attempt = 1; !cancelled; attempt++) {
                    try {
                        HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json)).build();
                        HttpResponse<InputStream> response = client.send(http, HttpResponse.BodyHandlers.ofInputStream());
                        body = response.body();
                        if (cancelled) { closeBody(); return; }
                        int status = response.statusCode();
                        if (status < 200 || status >= 300) {
                            closeBody(); body = null;
                            boolean retryable = status == 429 || status == 502 || status == 503 || status == 504;
                            if (retryPolicy.shouldRetry(attempt, emitted, retryable)) { Thread.sleep(retryPolicy.delay().toMillis()); continue; }
                            throw new IOException("model HTTP status " + status);
                        }
                        String type = response.headers().firstValue("content-type").orElse("").toLowerCase(java.util.Locale.ROOT);
                        if (!type.startsWith("text/event-stream")) throw new IOException("model response is not an SSE stream");
                        readStream(body);
                        if (!cancelled) { terminated = true; subscriber.onComplete(); }
                        else if (protocolError != null) { terminated = true; subscriber.onError(protocolError); }
                        return;
                    } catch (IOException e) {
                        if (cancelled) return;
                        // 仅连接或读取 IO 失败且没有输出时重试；协议错误不重试。
                        if (!(e instanceof ProtocolFailure) && retryPolicy.shouldRetry(attempt, emitted, isTransient(e))) {
                            closeBody(); body = null; Thread.sleep(retryPolicy.delay().toMillis()); continue;
                        }
                        throw e;
                    } finally { closeBody(); body = null; }
                }
            } catch (Exception e) {
                if (!cancelled) { terminated = true; subscriber.onError(new IOException("model generation failed", e)); }
                else if (protocolError != null) { terminated = true; subscriber.onError(protocolError); }
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally { worker = null; }
        }
        private boolean isTransient(IOException e) {
            return e instanceof java.net.ConnectException || e instanceof java.net.SocketException || e instanceof java.net.http.HttpTimeoutException || e instanceof java.io.EOFException;
        }
        private void readStream(InputStream in) throws IOException, InterruptedException {
            StreamAssembler assembler = new StreamAssembler();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> data = new ArrayList<>();
                String line;
                while (!cancelled && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            List<StreamChunk> events;
                            try { events = assembler.accept(String.join("\n", data)); }
                            catch (IOException | IllegalArgumentException e) { throw new ProtocolFailure("invalid SSE event", e); }
                            data.clear();
                            for (StreamChunk event : events) if (!emit(event)) return;
                            if (assembler.isDone()) return;
                        }
                    } else if (line.startsWith("data:")) {
                        String value = line.substring(5); data.add(value.startsWith(" ") ? value.substring(1) : value);
                    }
                }
                if (!cancelled && !assembler.isDone()) throw new java.io.EOFException("model stream ended before completion");
            }
        }
    }
    private static final class ProtocolFailure extends IOException { ProtocolFailure(String message, Throwable cause) { super(message, cause); } }
    /** 停止适配器的工作线程；调用方在应用关闭时负责调用。 */
    @Override public void close() { executor.shutdownNow(); }
}
