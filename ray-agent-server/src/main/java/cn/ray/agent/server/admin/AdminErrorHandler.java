package cn.ray.agent.server.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** 管理输入可能含密钥，错误响应只给固定提示，不回显字段原值或第三方响应体。 */
@RestControllerAdvice(basePackages="cn.ray.agent.server.admin")
public class AdminErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error){return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"操作未完成":error.getReason()));}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> input(Exception error){return ResponseEntity.badRequest().body(Map.of("message","配置字段或操作参数不合法，请检查后重试"));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failed(Exception error){return ResponseEntity.internalServerError().body(Map.of("message","后台操作未完成，请刷新确认状态并检查本机配置"));}
}
