package cn.bitsleep.tdl.worker;

import cn.bitsleep.tdl.domain.TodoStatus;
import cn.bitsleep.tdl.repo.TodoItemRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class Workers {

    private final RedissonClient redissonClient;
    private final TodoItemRepository repo;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private static final String DELETE_QUEUE = "tdl:todo:delete";
    private static final String EMBED_QUEUE = "tdl:todo:embed";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Poll delete queue periodically and perform physical delete if still trashed
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void consumeDeleteQueue() {
        RQueue<String> queue = redissonClient.getQueue(DELETE_QUEUE);
        String job;
        while ((job = queue.poll()) != null) {
            try {
                String[] parts = job.split(":", 2);
                String userId = parts[0];
                String id = parts[1];
                var opt = repo.findById(id);
                if (opt.isPresent()) {
                    var item = opt.get();
                    if (userId.equals(item.getUserId()) && item.getStatus() == TodoStatus.TRASHED
                            && item.getTrashPurgeAt() != null && Instant.now().isAfter(item.getTrashPurgeAt())) {
                        repo.hardDelete(id, userId);
                        log.info("Hard deleted todo {} for user {}", id, userId);
                    } else {
                        log.info("Skip hard delete for {}, status={} purgeAt={}", id, item.getStatus(), item.getTrashPurgeAt());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed processing delete job {}", job, e);
            }
        }
    }

    // Poll embedding queue and update embedding via EmbeddingStore
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void consumeEmbeddingQueue() {
        RQueue<String> queue = redissonClient.getQueue(EMBED_QUEUE);
        String job;
        while ((job = queue.poll()) != null) {
            try {
                String[] parts = job.split(":", 2);
                String userId = parts[0];
                String id = parts[1];
                var opt = repo.findById(id);
                if (opt.isEmpty()) continue;
                var item = opt.get();
                String text = Optional.ofNullable(item.getEmbeddingText()).orElse("");
                var result = embeddingModel.embed(text);
                Embedding embedding = result.content();

                // build metadata JSON safely (skip nulls)
                Map<String, Object> md = new HashMap<>();
                md.put("userId", userId);
                md.put("status", item.getStatus() != null ? item.getStatus().name() : null);
                if (item.getCategoryId() != null) md.put("categoryId", item.getCategoryId());
                if (item.getPriorityLabel() != null) md.put("priority", item.getPriorityLabel());

                String vecLiteral = toVectorLiteral(embedding.vectorAsList());
                String mdJson = MAPPER.writeValueAsString(md);
                repo.updateEmbedding(id, userId, vecLiteral, text, mdJson);
            } catch (Exception e) {
                log.warn("Failed processing embed job {}", job, e);
            }
        }
    }

    private String toVectorLiteral(java.util.List<Float> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            float v = values.get(i);
            // format compactly
            sb.append(Double.toString(v));
        }
        sb.append(']');
        return sb.toString();
    }
}
