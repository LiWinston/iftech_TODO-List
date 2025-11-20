package cn.bitsleep.tdl.web;

import org.hibernate.StaleObjectStateException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private ResponseEntity<Map<String,Object>> body(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", status.value(),
                        "error", code,
                        "message", msg
                ));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, StaleObjectStateException.class})
    public ResponseEntity<Map<String,Object>> handleOptimistic(Exception e) {
        return body(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK", e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, BindException.class})
    public ResponseEntity<Map<String,Object>> handleBadRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handleGeneric(Exception e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
    }

    // Spring 6+ 静态资源 / 控制器未匹配的 404，不再冒泡为 500
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String,Object>> handleNotFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }
}
