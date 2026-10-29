package cn.ray.agent.server.knowledge;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;

/** 只展示应用主动整理的错误，不把数据库、文件路径或供应商异常原文传给页面。 */
@RestControllerAdvice(assignableTypes = DocumentUploadController.class)
public class KnowledgeApiAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> expected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason() == null ? "操作失败" : error.getReason()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("message", "文件不能超过 5 MB"));
    }
}
