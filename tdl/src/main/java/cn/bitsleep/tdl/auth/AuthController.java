package cn.bitsleep.tdl.auth;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final TokenService tokenService;

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginReq req) {
        // 简化：任何 username / userId 密码均通过并签发 token
        String user = req.getUsername() != null ? req.getUsername() : req.getUserId();
        String token = tokenService.issue(user);
        return Map.of("token", token, "userId", user, "username", user);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("userId", authentication.getName()));
    }

    @Data
    public static class LoginReq {
        @JsonAlias({"userId"})
        private String username; // 统一前端字段
        private String userId; // 兼容旧字段（可选）
        private String password; // unused demo

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
