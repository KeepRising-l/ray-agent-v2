package cn.ray.agent.server.admin;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class ModelSettingsController {
    private final AdminAccessPolicy access;
    private final ModelSettingsService models;
    private final AdminOperationService audit;
    public ModelSettingsController(AdminAccessPolicy access,ModelSettingsService models,AdminOperationService audit){this.access=access;this.models=models;this.audit=audit;}
    @GetMapping("/models") public Map<String,Object> models(){access.owner();return models.modelView();}
    @PutMapping("/models/{id}") public Map<String,Object> model(@PathVariable String id,@RequestBody ModelSettingsService.ModelInput input){models.updateModel(id,input,access.owner(),access.username());return models.modelView();}
    @GetMapping("/embedding") public Map<String,Object> embedding(){access.owner();return models.embeddingView();}
    @PutMapping("/embedding") public Map<String,Object> embedding(@RequestBody ModelSettingsService.EmbeddingInput input){models.updateEmbedding(input,access.owner(),access.username());return models.embeddingView();}
    @PostMapping("/models/{id}/probe") public Map<String,Object> probeModel(@PathVariable String id,@RequestBody Probe input){
        return audit.execute(access.owner(),access.username(),"MODEL_PROBE",id,input.requestId(),()->{
            try {if(!models.probeModel(id).get("ok").equals(true))throw new IllegalStateException();return id;}
            catch(Exception e){throw new IllegalStateException("模型连通性检查失败");}
        });
    }
    @PostMapping("/embedding/probe") public Map<String,Object> probeEmbedding(@RequestBody Probe input){
        return audit.execute(access.owner(),access.username(),"EMBEDDING_PROBE","embedding",input.requestId(),()->models.probeEmbedding().get("dimension").toString());
    }
    public record Probe(String requestId) { }
}
