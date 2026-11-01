package cn.ray.agent.server.admin;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.function.Supplier;

/** 先持久声明操作身份，再执行明确范围的补偿；不承诺外部副作用exactly-once。 */
@Service
public class AdminOperationService {
    private final AdminSettingsStore store;
    public AdminOperationService(AdminSettingsStore store) { this.store=store; }
    /** request是一次明确操作的幂等编号；work只允许调用方提供的取消/重试等固定业务动作。 */
    public Map<String,Object> execute(String owner,String actor,String action,String target,String request,Supplier<String> work) {
        if(request==null || !request.matches("[a-zA-Z0-9-]{8,80}"))throw new IllegalArgumentException("invalid request identity");
        boolean claimed=store.atomic(()->{
            store.jdbc().queryForList("SELECT id FROM ra_admin_lock WHERE id=1 FOR UPDATE");
            var previous=lookup(owner,action,request);
            if(previous!=null) {
                if(!previous.get("target").equals(target))throw new ResponseStatusException(HttpStatus.CONFLICT,"同一操作编号不能用于不同目标");
                return false;
            }
            store.jdbc().update("INSERT INTO ra_admin_audit VALUES (?,?,?,?,?,?, 'REQUESTED',NULL,NULL,?,NULL)",
                    UUID.randomUUID().toString(),owner,actor,action,target,request,System.currentTimeMillis());return true;
        });
        // 声明事务已经提交后再做实际工作；重复请求只读原记录，不重复执行work。
        if(claimed) {
            String state="SUCCEEDED",code="OK",result=null;
            try {result=work.get();}
            catch(ResponseStatusException error){state="FAILED";code="HTTP_"+error.getStatusCode().value();}
            catch(cn.ray.agent.server.rag.embedding.EmbeddingClient.Failure error){state="FAILED";code="EMBEDDING_"+error.code();}
            catch(RuntimeException error){state="FAILED";code="OPERATION_FAILED";}
            store.jdbc().update("UPDATE ra_admin_audit SET status=?,code=?,result_id=?,ended_at=? WHERE owner_id=? AND action=? AND request_id=? AND status='REQUESTED'",
                    state,code,result,System.currentTimeMillis(),owner,action,request);
        }
        return lookup(owner,action,request);
    }
    private Map<String,Object> lookup(String owner,String action,String request) {
        var rows=store.jdbc().queryForList("SELECT id,actor,action,target,request_id,status,code,result_id,created_at,ended_at FROM ra_admin_audit WHERE owner_id=? AND action=? AND request_id=?",owner,action,request);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Map<String,Object>> list(String owner) {
        return store.jdbc().queryForList("SELECT id,actor,action,target,request_id,status,code,result_id,created_at,ended_at FROM ra_admin_audit WHERE owner_id=? ORDER BY created_at DESC,id LIMIT 100",owner);
    }
}
