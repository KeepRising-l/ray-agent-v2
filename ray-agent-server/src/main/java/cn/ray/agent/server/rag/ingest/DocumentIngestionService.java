package cn.ray.agent.server.rag.ingest;

import cn.ray.agent.server.knowledge.*;
import cn.ray.agent.server.rag.chunk.*;
import cn.ray.agent.server.rag.embedding.EmbeddingClient;
import cn.ray.agent.server.rag.parse.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.concurrent.*;

/** 首版单消费者轮询持久任务；重启保留待处理任务，处理中断的任务由用户显式重试。 */
@Service
@DependsOn("runStateService")
public class DocumentIngestionService {
    private final DocumentRepository store;
    private final DocumentFileStore files;
    private final ParserSelector parsers;
    private final EmbeddingClient embedding;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "document-ingestion");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closing;

    public DocumentIngestionService(DocumentRepository store, DocumentFileStore files, ParserSelector parsers, EmbeddingClient embedding) {
        this.store = store;
        this.files = files;
        this.parsers = parsers;
        this.embedding = embedding;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker.scheduleWithFixedDelay(this::poll, 200, 300, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (closing) {
            return;
        }
        try {
            var work = store.claim();
            if (work != null) {
                process(work);
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("文档任务轮询失败，请检查数据库状态");
        }
    }

    /** 网络调用发生在发布事务外；中途失败不会发布部分向量或覆盖上一有效版本。 */
    public void process(DocumentRepository.Work work) {
        String id = work.version().id();
        String stage = "PARSING";
        try {
            byte[] bytes = files.read(work.storageKey(), work.version().sha256());
            var parser = parsers.select(work.version().fileType());
            if (!parser.version().equals(work.version().parserVersion()) || !ChunkPolicy.DEFAULT.id().equals(work.version().chunkPolicy())) {
                throw new DocumentParser.Failure("处理策略已变化，请重新上传生成新版本");
            }
            var sections = parser.parse(bytes);
            store.stage(id, "PARSING", "CHUNKING");
            stage = "CHUNKING";
            var chunks = new DocumentChunker().chunk(id, sections, ChunkPolicy.DEFAULT);
            store.saveChunks(work, chunks);
            stage = "EMBEDDING";
            var vectors = new ArrayList<float[]>();
            for (int offset = 0; offset < chunks.size(); offset += 8) {
                if (closing || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException();
                }
                var batch = chunks.subList(offset, Math.min(offset + 8, chunks.size()));
                vectors.addAll(embedding.embed(batch.stream().map(DocumentChunk::content).toList()));
            }
            stage = "SAVING";
            store.publish(work, embedding.profile(), chunks, vectors);
        } catch (DocumentParser.Failure e) {
            store.fail(id, stage, e.getMessage(), false);
        } catch (EmbeddingClient.Failure e) {
            store.fail(id, stage, e.getMessage(), false);
        } catch (java.io.IOException e) {
            store.fail(id, stage, "原文件读取失败，或文件摘要与上传记录不一致", false);
        } catch (Exception e) {
            boolean interrupted = closing || e instanceof CancellationException || Thread.currentThread().isInterrupted();
            store.fail(id, stage, interrupted ? "服务关闭，文档处理已中断，请重试" : "文档处理失败，请检查文件、存储或数据库后重试", interrupted);
        }
    }

    @PreDestroy
    public void close() {
        closing = true;
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
