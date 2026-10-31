package cn.ray.agent.server.run;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class IdempotencyAdvice {
    @ExceptionHandler(RequestIdempotencyService.Duplicate.class)
    public ResponseEntity<Map<String,Object>> duplicate(RequestIdempotencyService.Duplicate error) {
        return ResponseEntity.status(409).header("Location","/api/runs/"+error.runId).body(Map.of(
                "message",error.getReason(),"runId",error.runId,"status",error.state,"sameInput",error.sameInput));
    }
}
