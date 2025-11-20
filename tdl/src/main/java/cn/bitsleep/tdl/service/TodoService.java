package cn.bitsleep.tdl.service;

import cn.bitsleep.tdl.domain.TodoItem;
import cn.bitsleep.tdl.domain.TodoStatus;
import cn.bitsleep.tdl.repo.TodoItemRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
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
        Short[] codes = statuses.stream().map(s -> (short) s.code).toArray(Short[]::new);
        if (cursorCreatedAt == null || cursorId == null) {
            return repo.firstPage(userId, codes, size);
        }
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

    public List<TodoItem> hybridSearch(String userId, String query, int k) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return java.util.Collections.emptyList();

        int qLen = q.codePointCount(0, q.length());
        // 动态阈值：短查询放宽，长查询提高
        double semanticMin = qLen < 3 ? 0.0 : 0.55; // 短词不做语义阈值
        double textMin = qLen < 3 ? 0.0 : 0.20;     // 短词不做文本阈值
        double alpha = 0.6; // 语义权重
        double containsBoost = 0.25; // 命中子串加成

        var qEmbedding = embeddingModel.embed(q).content();
        EmbeddingSearchResult<TextSegment> vecRes = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(qEmbedding)
                        .maxResults(k)
                        .build()
        );

    List<Object[]> textRows = repo.textSearch(userId, q, k);

        java.util.Map<String, Double> score = new java.util.HashMap<>();

        // 语义候选
        vecRes.matches().forEach(m -> {
            String id = m.embeddingId();
            double sim = m.score();
            if (sim >= semanticMin) {
                score.merge(id, alpha * sim, Double::sum);
            }
        });

        // 文本候选（带 title 优先级增强）
        for (Object[] row : textRows) {
            String id = (String) row[0];
            double s = ((Number) row[1]).doubleValue();
            boolean titleExact = Boolean.TRUE.equals(row[2]);
            boolean titlePrefix = Boolean.TRUE.equals(row[3]);

            if (s >= textMin) {
                double prev = score.getOrDefault(id, 0.0);
                double boost = containsBoost;
                if (titleExact) boost += 0.35; // 标题完全匹配强力提升
                else if (titlePrefix) boost += 0.20; // 标题前缀匹配中度提升

                double combined = prev + (1 - alpha) * s + boost;
                // 限制上限到 1.5，避免极端叠加
                if (combined > 1.5) combined = 1.5;
                score.put(id, combined);
            }
        }

        // 如果结果过多且查询较长，可进行二次过滤（例如保留前 k）
        if (score.isEmpty()) return java.util.Collections.emptyList();

        // fetch items and sort by score
    java.util.List<TodoItem> items = repo.findAllById(score.keySet()).stream()
                .filter(it -> userId.equals(it.getUserId()) && it.getStatus() != TodoStatus.TRASHED)
                .sorted((a,b) -> Double.compare(score.getOrDefault(b.getId(),0.0), score.getOrDefault(a.getId(),0.0)))
                .limit(k)
                .toList();
        return items;
    }
}
