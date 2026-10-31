package cn.ray.agent.server.task;

import cn.ray.agent.server.conversation.ConversationRepository;
import cn.ray.agent.server.knowledge.DocumentRepository;
import org.springframework.stereotype.Component;

/** 已取得单实例数据库锁后执行：旧QUEUED/RUNNING标中断，等待用户保留，文档阶段标中断后允许显式重试。 */
@Component
public class InterruptedRunRecovery {
    private final ConversationRepository runs;
    private final DocumentRepository documents;

    public InterruptedRunRecovery(ConversationRepository runs, DocumentRepository documents) {
        this.runs = runs;
        this.documents = documents;
    }

    public void recover() {
        runs.recover();
        documents.recover();
    }
}
