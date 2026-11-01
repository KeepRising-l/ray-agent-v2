package cn.ray.agent.server.admin;

import cn.ray.agent.server.model.ModelChannelService;
import cn.ray.agent.server.rag.embedding.*;
import cn.ray.agent.runtime.model.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** 非秘密配置持久化、密钥本机引用、客户端版本与生效时机统一在此管理。 */
@Service
public class ModelSettingsService {
    private final AdminSettingsStore store;
    private final LocalSecretStore secrets;
    private final ModelChannelService channels;
    private final OpenAiEmbeddingClient embedding;
    private final Environment env;
    // 启动恢复配置完成后才允许接收新run；volatile让请求线程看到初始化结果。
    private volatile boolean ready;

    public ModelSettingsService(AdminSettingsStore store,LocalSecretStore secrets,ModelChannelService channels,
                                OpenAiEmbeddingClient embedding,Environment env) {
        this.store=store;this.secrets=secrets;this.channels=channels;this.embedding=embedding;this.env=env;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Order(-100)
    public void initialize() {
        synchronized(store.monitor()) { apply(catalogue());applyEmbedding(embeddingSetting());ready=true; }
    }
    public void requireReady() {
        if(!ready) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"运行配置尚未就绪，请稍后重试");
    }
    public boolean isReady(){return ready;}
    /** 已保存的配置优先；尚未保存时才把本机环境中的渠道作为初始目录。 */
    public Catalogue catalogue() {
        var saved=store.find("model-catalogue");
        if(saved!=null) return store.parse(saved.payload(),Catalogue.class);
        var definitions=new ArrayList<Definition>();
        for(var channel:channels.list()) {
            String prefix=channel.id().equals("openai-compatible")?"ray.model.":"ray.model.channel."+channel.id()+".";
            String base=env.getProperty(prefix+"base-url",env.getProperty("ray.model.base-url",""));
            String reference=env.containsProperty(prefix+"api-key")?prefix+"api-key":"ray.model.api-key";
            definitions.add(new Definition(channel.id(),base(base),channel.model(),true,"env:"+reference));
        }
        return new Catalogue(0,definitions.isEmpty()?"":definitions.get(0).id(),List.copyOf(definitions));
    }
    public EmbeddingSetting embeddingSetting() {
        var saved=store.find("embedding-profile");
        if(saved!=null) return store.parse(saved.payload(),EmbeddingSetting.class);
        return new EmbeddingSetting(0,env.getProperty("ray.embedding.base-url","https://api.siliconflow.cn/v1").replaceAll("/+$",""),
                env.getProperty("ray.embedding.model","BAAI/bge-m3"),env.getProperty("ray.embedding.dimension",Integer.class,1024),
                env.getProperty("ray.embedding.revision","v1"),"env:ray.embedding.api-key");
    }
    public Map<String,Object> modelView() {
        var catalog=catalogue();
        return Map.of("revision",catalog.revision(),"defaultChannel",catalog.defaultChannel(),"channels",catalog.channels().stream().map(d -> Map.of(
                "id",d.id(),"baseUrl",d.baseUrl(),"model",d.model(),"enabled",d.enabled(),"configured",!secrets.resolve(d.keyRef()).isBlank(),"version",version(d))).toList());
    }
    public Map<String,Object> embeddingView() {
        var value=embeddingSetting();
        return Map.of("revision",value.revision(),"baseUrl",value.baseUrl(),"model",value.model(),"dimension",value.dimension(),
                "version",value.version(),"profileId",embedding.profile().id(),"configured",embedding.configured());
    }
    /** revision是页面读取的版本；owner限定数据范围，actor记录本次实际登录的操作者。 */
    public void updateModel(String id,ModelInput input,String owner,String actor) {
        requireReady();
        if(!id.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("invalid channel");
        String base=base(input.baseUrl());checkModel(input.model());
        synchronized(store.monitor()) {
            var current=catalogue();
            if(current.revision()!=input.revision()) throw new ResponseStatusException(HttpStatus.CONFLICT,"配置已变化，请刷新");
            var definitions=new ArrayList<>(current.channels());
            var old=definitions.stream().filter(d->d.id().equals(id)).findFirst().orElse(null);
            if(old==null && definitions.size()>=10) throw new ResponseStatusException(HttpStatus.CONFLICT,"首版最多配置10个模型渠道");
            String reference=secrets.replace(old==null?"":old.keyRef(),input.apiKey(),input.clearKey());
            definitions.removeIf(d->d.id().equals(id));
            definitions.add(new Definition(id,base,input.model(),input.enabled(),reference));
            String selected=input.makeDefault()?id:current.defaultChannel();
            String selectedId=selected;
            if(definitions.stream().noneMatch(d->d.id().equals(selectedId)&&d.enabled()&&!secrets.resolve(d.keyRef()).isBlank())) {
                selected=definitions.stream().filter(d->d.enabled()&&!secrets.resolve(d.keyRef()).isBlank()).map(Definition::id).findFirst().orElse("");
            }
            var next=new Catalogue(current.revision()+1,selected,List.copyOf(definitions));
            store.update("model-catalogue",input.revision(),next,owner,actor);apply(next);
        }
    }
    public void updateEmbedding(EmbeddingInput input,String owner,String actor) {
        requireReady();String base=base(input.baseUrl());checkModel(input.model());
        new EmbeddingProfile(URI.create(base+"/embeddings"),input.model(),input.dimension(),input.version());
        synchronized(store.monitor()) {
            // 和新run入库、文档领取共用同一把短锁，检查后不会夹入一个旧向量空间任务。
            int active=store.jdbc().queryForObject("SELECT COUNT(*) FROM ra_run WHERE status IN ('QUEUED','RUNNING','WAITING_USER')",Integer.class)
                    +store.jdbc().queryForObject("SELECT COUNT(*) FROM ra_document_version WHERE status IN ('UPLOADED','PARSING','CHUNKING','EMBEDDING')",Integer.class);
            if(active>0 || store.inFlightDocuments()>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先结束活动问答、等待补充与文档处理，再修改向量配置");
            var old=embeddingSetting();
            if(old.revision()!=input.revision()) throw new ResponseStatusException(HttpStatus.CONFLICT,"配置已变化，请刷新");
            var value=new EmbeddingSetting(old.revision()+1,base,input.model(),input.dimension(),input.version(),
                    secrets.replace(old.keyRef(),input.apiKey(),input.clearKey()));
            store.update("embedding-profile",input.revision(),value,owner,actor);applyEmbedding(value);
        }
    }
    private void apply(Catalogue value) {
        channels.install(value.channels().stream().map(d->new ModelChannelService.RuntimeChannel(d.id(),d.baseUrl(),d.model(),
                secrets.resolve(d.keyRef()),d.enabled(),version(d))).toList(),value.defaultChannel());
    }
    private void applyEmbedding(EmbeddingSetting value) {
        embedding.replaceConfiguration(new EmbeddingProfile(URI.create(value.baseUrl()+"/embeddings"),value.model(),value.dimension(),value.version()),secrets.resolve(value.keyRef()));
    }
    /** 调用方持有配置短锁，在同一时点捕获模型、策略与向量空间；快照绝不包含密钥。 */
    public RunSnapshotService.Snapshot snapshot(ModelChannelService.ChannelView channel,RuntimePolicyService.Policy policy) {
        var definition=catalogue().channels().stream().filter(d->d.id().equals(channel.id())).findFirst().orElseThrow();
        return new RunSnapshotService.Snapshot(channel.id(),channel.model(),definition.baseUrl(),channel.version(),policy,
                embedding.profile().id(),embedding.profile().model(),embedding.profile().dimension());
    }
    public Map<String,Object> probeEmbedding() {
        synchronized(store.monitor()) { var vectors=embedding.embed(List.of("连接检查"));return Map.of("ok",true,"dimension",vectors.get(0).length); }
    }
    public Map<String,Object> probeModel(String id) throws Exception {
        Definition definition;
        synchronized(store.monitor()) { definition=catalogue().channels().stream().filter(d->d.id().equals(id)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"渠道不存在")); }
        try(var adapter=new OpenAiCompatibleAdapter(URI.create(definition.baseUrl()+"/chat/completions"),secrets.resolve(definition.keyRef()),
                definition.model(),new RetryPolicy(1,Duration.ZERO))) {
            var result=adapter.generate(new GenerateRequest(List.of(new Message(Message.Role.USER,"只回复OK")),new GenerateOptions(null,0.0,16),List.of()));
            var done=new java.util.concurrent.CompletableFuture<Boolean>();
            result.subscribe(new java.util.concurrent.Flow.Subscriber<StreamChunk>() {
                java.util.concurrent.Flow.Subscription subscription;boolean text;
                public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(Long.MAX_VALUE);}
                public void onNext(StreamChunk chunk){if(chunk instanceof StreamChunk.TextDelta delta && !delta.text().isBlank())text=true;}
                public void onError(Throwable error){done.complete(false);}
                public void onComplete(){done.complete(text);}
            });
            return Map.of("ok",done.get(30,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private static String base(String raw) {
        if(raw==null || raw.length()>512) throw new IllegalArgumentException("invalid endpoint");
        var uri=URI.create(raw.replaceAll("/+$",""));
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !Set.of("http","https").contains(uri.getScheme())) throw new IllegalArgumentException("invalid endpoint");
        return uri.toString();
    }
    private static void checkModel(String model) { if(model==null || model.isBlank() || model.length()>200)throw new IllegalArgumentException("invalid model"); }
    private String version(Definition definition) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(store.json(definition).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("configuration version unavailable");}
    }
    /** id是用户选择的渠道名，model是上游模型名；keyRef只指向本机密钥，不是密钥正文。 */
    public record Definition(String id,String baseUrl,String model,boolean enabled,String keyRef) { }
    /** revision用于防止旧页面覆盖新配置；defaultChannel只影响之后选择默认渠道的新请求。 */
    public record Catalogue(long revision,String defaultChannel,List<Definition> channels) { }
    /** revision是配置编辑版本，version是向量空间业务版本，dimension是每条向量的长度。 */
    public record EmbeddingSetting(long revision,String baseUrl,String model,int dimension,String version,String keyRef) { }
    public record ModelInput(long revision,String baseUrl,String model,boolean enabled,boolean makeDefault,String apiKey,boolean clearKey) {
        @Override public String toString(){return "ModelInput[credentials redacted]";}
    }
    public record EmbeddingInput(long revision,String baseUrl,String model,int dimension,String version,String apiKey,boolean clearKey) {
        @Override public String toString(){return "EmbeddingInput[credentials redacted]";}
    }
}
