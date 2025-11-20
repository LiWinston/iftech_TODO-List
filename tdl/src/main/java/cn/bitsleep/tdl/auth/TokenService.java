package cn.bitsleep.tdl.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    private final SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    private final Map<String, String> userTokens = new ConcurrentHashMap<>();

    public String issue(String userId) {
        String jws = Jwts.builder()
                .setSubject(userId)
                .setIssuedAt(new Date())
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();
        userTokens.put(userId, jws);
        return jws;
    }

    public String validate(String token) {
        try {
            String sub = Jwts.parserBuilder().setSigningKey(key).build()
                    .parseClaimsJws(token).getBody().getSubject();
            String stored = userTokens.get(sub);
            if (stored != null && stored.equals(token)) return sub;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
