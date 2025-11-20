package cn.bitsleep.tdl.service;

import cn.bitsleep.tdl.domain.TodoItem;
import cn.bitsleep.tdl.domain.TodoStatus;
import cn.bitsleep.tdl.repo.TodoItemRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TodoService {

    private final TodoItemRepository repo;
    private final RedissonClient redissonClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${tdl.delete.delay-seconds:604800}")
    private long deleteDelaySeconds;

    private static final String DELETE_QUEUE = "tdl:todo:delete";
    private static final String EMBED_QUEUE = "tdl:todo:embed";

    public List<TodoItem> list(String userId, List<TodoStatus> statuses, Instant cursorCreatedAt, String cursorId, int size) {
        var codes = statuses.stream().map(s -> s.code).toList();
        return repo.keysetPage(userId, codes, cursorCreatedAt, cursorId, size);
    }

    @Transactional
    public TodoItem create(String userId, String title, String description, BigDecimal priorityScore, String priorityLabel, String categoryId) {
        TodoItem item = TodoItem.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .title(title)
                .description(description)
                .priorityScore(priorityScore == null ? BigDecimal.ZERO : priorityScore)
                .priorityLabel(priorityLabel)
                .categoryId(categoryId)
                .statusCode(TodoStatus.ACTIVE.code)
                .version(0L)
                .embeddingText(buildEmbeddingText(title, description))
                .metadata("{}")
                .build();
        repo.save(item);
        enqueueEmbeddingJob(item.getId(), userId);
        return item;
    }

    @Transactional
    public void updateContent(String id, String userId, String title, String description, BigDecimal priorityScore, String priorityLabel, String categoryId) {
        String text = buildEmbeddingText(title, description);
        String metadata = "{" + "\"userId\":\"" + userId + "\"}";
        repo.updateContent(id, userId, title, description, priorityScore, priorityLabel, categoryId, text, metadata);
        enqueueEmbeddingJob(id, userId);
    }

    @Transactional
    public void complete(String id, String userId) {
        repo.updateStatus(id, userId, TodoStatus.COMPLETED.code, null);
    }

    @Transactional
    public void uncomplete(String id, String userId) {
        repo.updateStatus(id, userId, TodoStatus.ACTIVE.code, null);
    }

    @Transactional
    public void softDelete(String id, String userId) {
        Instant purgeAt = Instant.now().plusSeconds(deleteDelaySeconds);
        int updated = repo.updateStatus(id, userId, TodoStatus.TRASHED.code, purgeAt);
        if (updated > 0) {
            RQueue<String> queue = redissonClient.getQueue(DELETE_QUEUE);
            RDelayedQueue<String> delayed = redissonClient.getDelayedQueue(queue);
            delayed.offer(userId + ":" + id, deleteDelaySeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Transactional
    public void restore(String id, String userId) {
        repo.updateStatus(id, userId, TodoStatus.ACTIVE.code, null);
    }

    @Transactional
    public void hardDelete(String id, String userId) {
        repo.hardDelete(id, userId);
    }

    private void enqueueEmbeddingJob(String id, String userId) {
        RQueue<String> queue = redissonClient.getQueue(EMBED_QUEUE);
        queue.offer(userId + ":" + id);
    }

    private String buildEmbeddingText(String title, String description) {
        return (title == null ? "" : title) + "\n" + (description == null ? "" : description);
    }
}
