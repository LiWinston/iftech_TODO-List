package cn.bitsleep.tdl.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

// NOTE: The concrete AllMiniLmL6V2EmbeddingModel class is provided by
// langchain4j-embeddings-all-minilm-l6-v2 artifact.
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

@Configuration
public class AppConfig {

    @Value("${tdl.redis.url}")
    private String redisUrl;

    @Value("${tdl.embedding.dimension:384}")
    private int embeddingDimension;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        // Expected format: rediss://username:password@host:port
        // We'll parse to set username/password explicitly for ACL.
        // Redisson supports address with rediss scheme for TLS.
        String address;
        String username = null;
        String password = null;

        try {
            java.net.URI uri = new java.net.URI(redisUrl);
            address = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                if (parts.length == 2) {
                    username = parts[0];
                    password = parts[1];
                } else {
                    password = userInfo; // password only
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Redis URL: " + redisUrl, e);
        }

        Config config = new Config();
        var single = config.useSingleServer().setAddress(address);
        if (username != null && !username.isEmpty()) {
            single.setUsername(username);
        }
        if (password != null && !password.isEmpty()) {
            single.setPassword(password);
        }
        // TLS is auto with rediss://
        single.setSslEnableEndpointIdentification(true);

        return Redisson.create(config);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        // Loads ONNX model in memory; by default uses CPU.
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<?> embeddingStore(DataSource dataSource, EmbeddingModel model) {
        // We point to the same table as domain data to keep data & embeddings in one row.
        // Important: the table must contain at least columns expected by PgVectorEmbeddingStore:
        // id (TEXT/UUID-as-text), embedding (vector(dimension)), text (TEXT), metadata (JSON/JSONB).
        // We disable createTable because JPA will manage DDL.
        return PgVectorEmbeddingStore.builder()
                .datasource(dataSource)
                .table("todo_item")
                .dimension(model.dimension())
                .createTable(false)
                .metadataStorageConfig(MetadataStorageConfig.combinedJsonb())
                .build();
    }
}
