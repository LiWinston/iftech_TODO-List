package cn.bitsleep.tdl.auth;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final TokenService tokenService;

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginReq req) {
        // 简化：任何 userId 密码均通过并签发 token
        String token = tokenService.issue(req.getUserId());
        return Map.of("token", token, "userId", req.getUserId());
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("userId", authentication.getName()));
    }

    @Data
    public static class LoginReq {
        private String userId;
        private String password; // unused demo
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
