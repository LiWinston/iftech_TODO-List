package cn.bitsleep.tdl.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Configuration
public class SecurityConfig {

    private final TokenService tokenService;
    public SecurityConfig(TokenService tokenService) { this.tokenService = tokenService; }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .cors(c -> {})
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(reg -> reg
            // 允许登录与用户信息接口 & 预检请求
            .requestMatchers("/api/auth/login", "/api/auth/me").permitAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            // 公共只读查询接口
            .requestMatchers(HttpMethod.GET, "/api/todos", "/api/todos/search").permitAll()
            .anyRequest().authenticated()
        )
        .addFilterBefore(new JwtFilter(tokenService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class JwtFilter extends OncePerRequestFilter {
        private final TokenService tokenService;
        JwtFilter(TokenService tokenService){this.tokenService = tokenService;}
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7);
                String userId = tokenService.validate(token);
                if (userId != null) {
                    UserDetails ud = User.withUsername(userId).password("NOP").authorities(Collections.emptyList()).build();
                    var authToken = new UsernamePasswordAuthenticationToken(ud, token, ud.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    // propagate user id as header for existing service logic
                    request.setAttribute("X-User-ID", userId);
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
