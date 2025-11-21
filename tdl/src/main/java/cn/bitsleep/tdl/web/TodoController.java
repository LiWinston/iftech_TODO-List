package cn.bitsleep.tdl.web;

import cn.bitsleep.tdl.domain.TodoItem;
import cn.bitsleep.tdl.domain.TodoStatus;
import cn.bitsleep.tdl.service.TodoService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
@Validated
public class TodoController {

    private final TodoService service;

    private String userIdOrDefault(String header) {
        String user = null;
        if (header != null && !header.isBlank()) user = header;
        else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        }
        // Treat Spring Security anonymous principal as absent
        if ("anonymousUser".equals(user)) user = null;
        if (user == null || user.isBlank()) user = "demo"; // 匿名默认 demo
        if ("demo-user".equals(user)) user = "demo"; // 兼容早期默认用户
        return user.trim();
    }

    @GetMapping
    public List<TodoItem> list(
            @RequestHeader(value = "X-User-ID", required = false) String userHeader,
            @RequestParam(required = false) Instant cursorCreatedAt,
            @RequestParam(required = false) String cursorId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String status, // CSV of ACTIVE,COMPLETED,TRASHED
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order, // 预留（当前仅支持 DESC）
            @RequestParam(required = false) String priorityLevelId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String tags // CSV tag ids
    ) {
        String userId = userIdOrDefault(userHeader);
        List<TodoStatus> statuses = (status == null || status.isBlank()) ?
                List.of(TodoStatus.ACTIVE, TodoStatus.COMPLETED) :
                Arrays.stream(status.split(",")).map(String::trim).map(TodoStatus::valueOf).toList();
        List<String> tagIds = (tags == null || tags.isBlank()) ? null : Arrays.stream(tags.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
        return service.list(userId, statuses, cursorCreatedAt, cursorId, size, sort, order, priorityLevelId, categoryId, tagIds);
    }

    @PostMapping
    public TodoItem create(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                           @RequestBody CreateTodo req) {
        String userId = userIdOrDefault(userHeader);
        return service.create(userId, req.title, req.description, req.priorityScore, req.priorityLabel, req.categoryId, req.priorityLevelId, req.tagIds);
    }

    @GetMapping("/search")
    public List<TodoItem> search(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                 @RequestParam("q") String q,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(200) int k) {
        String userId = userIdOrDefault(userHeader);
        return service.hybridSearch(userId, q, k);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                    @PathVariable String id,
                                    @RequestBody UpdateTodo req) {
        String userId = userIdOrDefault(userHeader);
        service.updateContent(id, userId, req.title, req.description, req.priorityScore, req.priorityLabel, req.categoryId, req.priorityLevelId, req.tagIds);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                      @PathVariable String id) {
        String userId = userIdOrDefault(userHeader);
        service.complete(id, userId);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/{id}/uncomplete")
    public ResponseEntity<?> uncomplete(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                        @PathVariable String id) {
        String userId = userIdOrDefault(userHeader);
        service.uncomplete(id, userId);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> softDelete(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                        @PathVariable String id) {
        String userId = userIdOrDefault(userHeader);
        service.softDelete(id, userId);
        return ResponseEntity.ok(Map.of("id", id));
    }

    // 为前端当前使用的 POST /{id}/trash 兼容映射（软删除，与 DELETE /{id} 等价）
    @PostMapping("/{id}/trash")
    public ResponseEntity<?> trash(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                   @PathVariable String id) {
        String userId = userIdOrDefault(userHeader);
        service.softDelete(id, userId);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                     @PathVariable String id) {
        String userId = userIdOrDefault(userHeader);
        service.restore(id, userId);
        return ResponseEntity.ok(Map.of("id", id));
    }

    // 彻底删除（仅当已在回收站）
    @PostMapping("/{id}/purge")
    public ResponseEntity<?> purge(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                   @PathVariable String id) {
        String userId = userIdOrDefault(userHeader);
        service.purgeIfTrashed(id, userId);
        return ResponseEntity.ok(Map.of("id", id, "purged", true));
    }

    @Data
    public static class CreateTodo {
        @NotBlank
        public String title;
        public String description;
        public BigDecimal priorityScore;
        public String priorityLabel;
        public String categoryId;
        public String priorityLevelId;
        public java.util.List<String> tagIds;
    }

    @Data
    public static class UpdateTodo {
        public String title;
        public String description;
        public BigDecimal priorityScore;
        public String priorityLabel;
        public String categoryId;
        public String priorityLevelId;
        public java.util.List<String> tagIds;
    }
}
