package cn.bitsleep.tdl.web;

import cn.bitsleep.tdl.domain.PriorityLevel;
import cn.bitsleep.tdl.domain.Category;
import cn.bitsleep.tdl.domain.Tag;
import cn.bitsleep.tdl.service.PriorityService;
import cn.bitsleep.tdl.service.CategoryService;
import cn.bitsleep.tdl.service.TagService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Validated
public class ConfigController {

    private final PriorityService priorityService;
    private final CategoryService categoryService;
    private final TagService tagService;

    private String userIdOrDefault(String header) {
        String user = null;
        if (header != null && !header.isBlank()) user = header;
        else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) user = auth.getName();
        }
        if ("anonymousUser".equals(user)) user = null;
        if (user == null || user.isBlank()) user = "demo";
        if ("demo-user".equals(user)) user = "demo";
        return user.trim();
    }

    // ===== Priority Levels =====
    @GetMapping("/priority-levels")
    public List<PriorityLevel> listPriorities(@RequestHeader(value = "X-User-ID", required = false) String userHeader) {
        return priorityService.list(userIdOrDefault(userHeader));
    }

    @PostMapping("/priority-levels")
    public PriorityLevel createPriority(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                        @RequestBody CreatePriority req) {
        return priorityService.create(userIdOrDefault(userHeader), req.name, req.afterId, req.beforeId);
    }

    @PatchMapping("/priority-levels/{id}")
    public ResponseEntity<?> patchPriority(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                           @PathVariable String id,
                                           @RequestBody PatchPriority req) {
        String userId = userIdOrDefault(userHeader);
        if (req.newName != null && !req.newName.isBlank()) {
            priorityService.rename(id, userId, req.newName.trim());
        }
        if (req.moveAfterId != null || req.moveBeforeId != null) {
            priorityService.moveBetween(id, userId, req.moveAfterId, req.moveBeforeId);
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/priority-levels/{id}")
    public ResponseEntity<?> deletePriority(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                            @PathVariable String id) {
        priorityService.delete(id, userIdOrDefault(userHeader));
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ===== Categories =====
    @GetMapping("/categories")
    public List<Category> listCategories(@RequestHeader(value = "X-User-ID", required = false) String userHeader) {
        return categoryService.list(userIdOrDefault(userHeader));
    }

    @PostMapping("/categories")
    public Category createCategory(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                   @RequestBody CreateCategory req) {
        return categoryService.create(userIdOrDefault(userHeader), req.name, req.color);
    }

    @PatchMapping("/categories/{id}")
    public ResponseEntity<?> patchCategory(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                           @PathVariable String id,
                                           @RequestBody PatchCategory req) {
        categoryService.rename(id, userIdOrDefault(userHeader), req.newName, req.color);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<?> deleteCategory(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                            @PathVariable String id) {
        categoryService.delete(id, userIdOrDefault(userHeader));
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ===== Tags =====
    @GetMapping("/tags")
    public List<Tag> listTags(@RequestHeader(value = "X-User-ID", required = false) String userHeader) {
        return tagService.list(userIdOrDefault(userHeader));
    }

    @PostMapping("/tags")
    public Tag createTag(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                         @RequestBody CreateTag req) {
        return tagService.create(userIdOrDefault(userHeader), req.name);
    }

    @PatchMapping("/tags/{id}")
    public ResponseEntity<?> patchTag(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                      @PathVariable String id,
                                      @RequestBody PatchTag req) {
        tagService.rename(id, userIdOrDefault(userHeader), req.newName);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/tags/{id}")
    public ResponseEntity<?> deleteTag(@RequestHeader(value = "X-User-ID", required = false) String userHeader,
                                       @PathVariable String id) {
        tagService.delete(id, userIdOrDefault(userHeader));
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ===== DTOs =====
    @Data public static class CreatePriority { @NotBlank public String name; public String afterId; public String beforeId; }
    @Data public static class PatchPriority { public String newName; public String moveAfterId; public String moveBeforeId; }
    @Data public static class CreateCategory { @NotBlank public String name; public String color; }
    @Data public static class PatchCategory { public String newName; public String color; }
    @Data public static class CreateTag { @NotBlank public String name; }
    @Data public static class PatchTag { public String newName; }
}
