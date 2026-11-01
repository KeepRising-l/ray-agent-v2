package cn.ray.agent.server.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.function.Supplier;

/** 配置只有非秘密字段与密钥引用；版本比较和审计在同一短事务完成。 */
@Repository
public class AdminSettingsStore {
    private final Object monitor = new Object();
    private int inFlightDocuments;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();
    public AdminSettingsStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }
    public <T> T atomic(Supplier<T> action) { return tx.execute(status -> action.get()); }
    // Repository可能被Spring代理，其他Bean通过方法访问目标对象状态，不直接访问代理字段。
    public Object monitor() { return monitor; }
    public JdbcTemplate jdbc() { return jdbc; }
    public int inFlightDocuments() { return inFlightDocuments; }
    public void documentStarted() { inFlightDocuments++; }
    public void documentFinished() { inFlightDocuments--; }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("invalid settings"); }
    }
    public <T> T parse(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("保存的配置格式不兼容"); }
    }
    public Entry find(String key) {
        return jdbc.query("SELECT revision,payload FROM ra_admin_settings WHERE id=?",
                (r,n) -> new Entry(r.getLong(1),r.getString(2)),key).stream().findFirst().orElse(null);
    }
    public long update(String key, long expected, Object value, String owner, String actor) {
        return atomic(() -> {
            jdbc.queryForList("SELECT id FROM ra_admin_lock WHERE id=1 FOR UPDATE");
            var old = find(key); long revision = old == null ? 0 : old.revision();
            if (revision != expected) throw new ResponseStatusException(HttpStatus.CONFLICT,"配置已变化，请刷新后再修改");
            jdbc.update("INSERT INTO ra_admin_settings VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET revision=EXCLUDED.revision,payload=EXCLUDED.payload,actor=EXCLUDED.actor,updated_at=EXCLUDED.updated_at",
                    key,revision+1,json(value),actor,System.currentTimeMillis());
            jdbc.update("INSERT INTO ra_admin_audit VALUES (?,?,?,?,?,?, 'SUCCEEDED',?,NULL,?,?)",
                    UUID.randomUUID().toString(),owner,actor,"SETTINGS_UPDATED",key,UUID.randomUUID().toString(),"REVISION_"+(revision+1),System.currentTimeMillis(),System.currentTimeMillis());
            return revision+1;
        });
    }
    public record Entry(long revision,String payload) { }
}
