package cn.ray.agent.server.admin;

import cn.ray.agent.server.conversation.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.function.Supplier;

/** 运行创建与配置留档共用数据库事务，避免出现有run却没有配置快照的半成品。 */
@Service
public class RunSnapshotService {
    private final AdminSettingsStore store;
    public RunSnapshotService(AdminSettingsStore store) { this.store=store; }
    public ConversationRepository.Run begin(String owner, Snapshot snapshot, Supplier<ConversationRepository.Run> create) {
        return store.atomic(() -> {
            var run=create.get();
            store.jdbc().update("INSERT INTO ra_run_snapshot VALUES (?,?,?,?)",run.id(),owner,store.json(snapshot),System.currentTimeMillis());
            return run;
        });
    }
    /** 旧运行没有快照时明确拒绝，不拿当前配置伪造它当时的配置。 */
    public Snapshot get(String owner,String run) {
        var values=store.jdbc().queryForList("SELECT payload FROM ra_run_snapshot WHERE run_id=? AND owner_id=?",String.class,run,owner);
        if(values.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"原运行没有可校验的配置快照，请取消后重新提问");
        return store.parse(values.get(0),Snapshot.class);
    }
    /** channelVersion校验接续时模型是否变化；policy固定该run预算，Embedding字段标识当时的索引空间。 */
    public record Snapshot(String channelId,String model,String baseUrl,String channelVersion,RuntimePolicyService.Policy policy,
                           String embeddingProfile,String embeddingModel,int embeddingDimension) { }
}
