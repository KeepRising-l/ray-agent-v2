package cn.ray.agent.server.admin;

import cn.ray.agent.server.chat.ChatApplicationService;
import cn.ray.agent.server.conversation.ConversationRepository;
import cn.ray.agent.server.knowledge.*;
import cn.ray.agent.server.rag.ingest.DocumentReindexService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 后台只编排已有补偿能力；版本、owner与动作范围校验后，再交给原业务服务。 */
@Service
public class TaskAdminService {
    private final AdminSettingsStore store;
    private final AdminOperationService operations;
    private final ConversationRepository conversations;
    private final ChatApplicationService chats;
    private final DocumentRepository documents;
    private final DocumentReindexService reindex;
    private final DocumentDeleteService deletion;
    public TaskAdminService(AdminSettingsStore store,AdminOperationService operations,ConversationRepository conversations,
                            ChatApplicationService chats,DocumentRepository documents,DocumentReindexService reindex,DocumentDeleteService deletion) {
        this.store=store;this.operations=operations;this.conversations=conversations;this.chats=chats;this.documents=documents;this.reindex=reindex;this.deletion=deletion;
    }
    public Map<String,Object> act(String owner,String actor,String kind,String id,Action input) {
        if(input.action()==null)throw new IllegalArgumentException("action required");
        if(kind.equals("RUN")) {
            conversations.run(owner,id);
            if(!input.action().equals("CANCEL"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"聊天只支持取消，接续请回到原会话回答");
            return operations.execute(owner,actor,"RUN_CANCEL",id,input.requestId(),()->chats.cancel(id).id());
        }
        if(!kind.equals("DOCUMENT") || !Set.of("RETRY","REINDEX","DELETE").contains(input.action()))throw new IllegalArgumentException("invalid task action");
        var rows=store.jdbc().queryForList("SELECT d.kb_id,d.id AS doc FROM ra_document_version v JOIN ra_document d ON d.id=v.document_id WHERE v.id=? AND d.owner_id=? AND d.deleted_at IS NULL",id,owner);
        // 重复DELETE仍能查询原审计结果；归属验证不能因删除而允许访问别人的记录。
        if(rows.isEmpty()) {
            rows=store.jdbc().queryForList("SELECT d.kb_id,d.id AS doc FROM ra_document_version v JOIN ra_document d ON d.id=v.document_id WHERE v.id=? AND d.owner_id=?",id,owner);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档任务不存在");
        }
        var row=rows.get(0);String kb=row.get("kb_id").toString(),doc=row.get("doc").toString();
        return operations.execute(owner,actor,"DOCUMENT_"+input.action(),id,input.requestId(),()->{
            synchronized(store.monitor()) {
                return switch(input.action()) {
                    case "RETRY" -> documents.retry(owner,kb,doc,id).id();
                    case "REINDEX" -> {
                        if(!documents.versions(owner,kb,doc).get(0).id().equals(id))throw new ResponseStatusException(HttpStatus.CONFLICT,"请切换最新文档版本后重建索引");
                        yield reindex.reindex(owner,kb,doc).id();
                    }
                    case "DELETE" -> {deletion.delete(owner,kb,doc);yield id;}
                    default -> throw new IllegalArgumentException();
                };
            }
        });
    }
    /** action为固定枚举字符串；requestId由前端对同一次点击及其查询重用。 */
    public record Action(String action,String requestId) { }
}
