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

                Metadata metadata = new Metadata();
                metadata.put("userId", userId);
                metadata.put("status", item.getStatus().name());
                metadata.put("categoryId", item.getCategoryId());
                metadata.put("priority", item.getPriorityLabel());

                // Use EmbeddingStore to upsert embedding & metadata into the same table
                // Assumes PgVectorEmbeddingStore uses upsert on id conflict.
                embeddingStore.add(id, embedding, TextSegment.from(text, metadata));
            } catch (Exception e) {
                log.warn("Failed processing embed job {}", job, e);
            }
        }
    }
}
