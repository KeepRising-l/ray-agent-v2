package cn.ray.agent.server.admin;

import cn.ray.agent.runtime.agent.RunBudget;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.*;

/** 策略在运行入队时取快照；不改变CHAT/KNOWLEDGE硬边界，不为任意插件开放接口。 */
@Service
public class RuntimePolicyService {
    private final AdminSettingsStore store;
    private final Policy defaults;
    public RuntimePolicyService(AdminSettingsStore store, Environment env) {
        this.store=store;
        defaults=new Policy(0,true,true,true,true,env.getProperty("ray.run.max-model-steps",Integer.class,5),
                env.getProperty("ray.run.max-tool-calls",Integer.class,6),env.getProperty("ray.run.timeout-ms",Long.class,60000L),
                env.getProperty("ray.run.tool-timeout-ms",Long.class,15000L));
    }
    public Policy current() {
        var entry=store.find("runtime-policy");
        return entry==null?defaults:store.parse(entry.payload(),Policy.class);
    }
    public Policy update(Policy input,String owner,String actor) {
        synchronized(store.monitor()) {
            var value=new Policy(input.revision()+1,input.searchEnabled(),input.fetchEnabled(),input.autoEnabled(),input.classifierEnabled(),
                    input.maxModelSteps(),input.maxToolCalls(),input.timeoutMs(),input.toolTimeoutMs());
            store.update("runtime-policy",input.revision(),value,owner,actor);return value;
        }
    }
    /** 不可变的运行策略：分别控制网页工具、AUTO及模型分类；次数与毫秒预算彼此独立。 */
    public record Policy(long revision,boolean searchEnabled,boolean fetchEnabled,boolean autoEnabled,boolean classifierEnabled,
                         int maxModelSteps,int maxToolCalls,long timeoutMs,long toolTimeoutMs) {
        public Policy {
            new RunBudget(maxModelSteps,maxToolCalls,timeoutMs,toolTimeoutMs,24000);
            if(revision<0 || timeoutMs<100 || timeoutMs>600000) throw new IllegalArgumentException("invalid policy");
        }
        public static Policy defaults() { return new Policy(0,true,true,true,true,5,6,60000,15000); }
        /** 只从已实现的工具中收缩允许名单；开关不能注册新工具或授权读知识库。 */
        public Set<String> tools() {
            var names=new HashSet<String>();names.add("ask_user");
            if(searchEnabled) names.add("web_search");if(fetchEnabled) names.add("web_fetch");return Set.copyOf(names);
        }
        public RunBudget budget() { return new RunBudget(maxModelSteps,maxToolCalls,timeoutMs,toolTimeoutMs,24000); }
    }
}
