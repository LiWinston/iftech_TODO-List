package cn.bitsleep.tdl.service;

import cn.bitsleep.tdl.domain.TodoItem;
import cn.bitsleep.tdl.domain.TodoStatus;
import cn.bitsleep.tdl.repo.TodoItemRepository;
import cn.bitsleep.tdl.repo.PriorityLevelRepository;
import cn.bitsleep.tdl.repo.CategoryRepository;
import cn.bitsleep.tdl.domain.Category;
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
    private final TagService tagService;
    private final PriorityLevelRepository priorityLevelRepository;
    private final CategoryRepository categoryRepository;

    @Value("${tdl.delete.delay-seconds:604800}")
    private long deleteDelaySeconds;

    private static final String DELETE_QUEUE = "tdl:todo:delete";
    private static final String EMBED_QUEUE = "tdl:todo:embed";

    public List<TodoItem> list(String userId, List<TodoStatus> statuses, Instant cursorCreatedAt, String cursorId, int size,
                               String sort, String order, String priorityLevelId, String categoryId, java.util.List<String> tagIds) {
        Short[] codes = statuses.stream().map(s -> (short) s.code).toArray(Short[]::new);
        boolean hasFilters = (priorityLevelId != null && !priorityLevelId.isBlank())
                || (categoryId != null && !categoryId.isBlank())
                || (tagIds != null && !tagIds.isEmpty());
        String tagsCsv = (tagIds == null || tagIds.isEmpty()) ? null : String.join(",", tagIds);
        boolean asc = "asc".equalsIgnoreCase(order);
        if (hasFilters || (sort != null && !sort.isBlank() && !"created".equals(sort))) {
            if (sort == null || sort.isBlank() || "created".equals(sort)) {
                return asc ? repo.filterCreatedAsc(userId, codes, priorityLevelId, categoryId, tagsCsv, size)
                           : repo.filterCreatedDesc(userId, codes, priorityLevelId, categoryId, tagsCsv, size);
            } else if ("priority".equals(sort)) {
                return asc ? repo.filterPriorityAsc(userId, codes, priorityLevelId, categoryId, tagsCsv, size)
                           : repo.filterPriorityDesc(userId, codes, priorityLevelId, categoryId, tagsCsv, size);
            }
        }
        // 基础 keyset 分页（仅对 created 排序）
        if (cursorCreatedAt == null || cursorId == null) {
            return asc ? repo.firstPageAsc(userId, codes, size) : repo.firstPage(userId, codes, size);
        }
        return asc ? repo.keysetPageAsc(userId, codes, cursorCreatedAt, cursorId, size)
                   : repo.keysetPage(userId, codes, cursorCreatedAt, cursorId, size);
    }

    @Transactional
    public TodoItem create(String userId, String title, String description, BigDecimal priorityScore, String priorityLabel, String categoryId, String priorityLevelId, java.util.List<String> tagIds) {
        // 若提供 priorityLevelId 则根据其 rank 派生 priorityScore（高优先级分数更大以便 DESC 排序）
        if (priorityLevelId != null && !priorityLevelId.isBlank()) {
            var plOpt = priorityLevelRepository.findById(priorityLevelId);
            if (plOpt.isPresent() && userId.equals(plOpt.get().getUserId())) {
                long rank = plOpt.get().getRank();
                // 简单线性映射：score = MAX_BASE - rank
                // MAX_BASE 取一个足够大的常量，避免出现负值并允许未来 rank 扩展
                long MAX_BASE = 2_000_000_000L;
                long derived = Math.max(1, MAX_BASE - rank);
                priorityScore = BigDecimal.valueOf(derived);
                if (priorityLabel == null || priorityLabel.isBlank()) {
                    priorityLabel = plOpt.get().getName();
                }
            }
        }
    String resolvedCategoryId = resolveCategoryId(userId, categoryId);
    TodoItem item = TodoItem.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .title(title)
                .description(description)
                .priorityScore(priorityScore == null ? BigDecimal.ZERO : priorityScore)
                .priorityLabel(priorityLabel)
        .categoryId(resolvedCategoryId)
                .priorityLevelId(priorityLevelId)
                .statusCode(TodoStatus.ACTIVE.code)
                .embeddingText(buildEmbeddingText(title, description))
                .metadata("{}")
                .build();
        repo.save(item);
        if (tagIds != null && !tagIds.isEmpty()) {
            tagService.setTagsForTodo(item.getId(), userId, tagIds);
        }
        enqueueEmbeddingJob(item.getId(), userId);
        return item;
    }

    @Transactional
    public void updateContent(String id, String userId, String title, String description, BigDecimal priorityScore, String priorityLabel, String categoryId, String priorityLevelId, java.util.List<String> tagIds) {
        String text = buildEmbeddingText(title, description);
        String metadata = "{" + "\"userId\":\"" + userId + "\"}";
        if (priorityLevelId != null && !priorityLevelId.isBlank()) {
            var plOpt = priorityLevelRepository.findById(priorityLevelId);
            if (plOpt.isPresent() && userId.equals(plOpt.get().getUserId())) {
                long rank = plOpt.get().getRank();
                long MAX_BASE = 2_000_000_000L;
                long derived = Math.max(1, MAX_BASE - rank);
                priorityScore = BigDecimal.valueOf(derived);
                if (priorityLabel == null || priorityLabel.isBlank()) {
                    priorityLabel = plOpt.get().getName();
                }
            }
        }
        String resolvedCategoryId = resolveCategoryId(userId, categoryId);
        repo.updateContent(id, userId, title, description, priorityScore, priorityLabel, resolvedCategoryId, priorityLevelId, text, metadata);
        if (tagIds != null) {
            tagService.setTagsForTodo(id, userId, tagIds);
        }
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

    @Transactional
    public void purgeIfTrashed(String id, String userId) {
        // 仅当任务当前处于 TRASHED 状态时才允许物理删除
        var opt = repo.findById(id);
        if (opt.isEmpty() || !userId.equals(opt.get().getUserId())) {
            throw new IllegalArgumentException("Item not found");
        }
        var item = opt.get();
        if (item.getStatus() != TodoStatus.TRASHED) {
            throw new IllegalStateException("Item not in trash");
        }
        repo.hardDelete(id, userId);
    }

    private void enqueueEmbeddingJob(String id, String userId) {
        RQueue<String> queue = redissonClient.getQueue(EMBED_QUEUE);
        queue.offer(userId + ":" + id);
    }

    private String buildEmbeddingText(String title, String description) {
        return (title == null ? "" : title) + "\n" + (description == null ? "" : description);
    }

    // 将前端传来的 categoryId 进行解析：若像 UUID 则直接返回；否则视为名称，查找或创建后返回其 id
    private String resolveCategoryId(String userId, String categoryIdOrName) {
        if (categoryIdOrName == null || categoryIdOrName.isBlank()) return null;
        String c = categoryIdOrName.trim();
        boolean looksUuid = c.length() == 36 && c.chars().filter(ch -> ch == '-').count() == 4;
        if (looksUuid) return c; // 认为是已有的 id
        // 按名称查找或创建
        var opt = categoryRepository.findByUserIdAndName(userId, c);
        if (opt.isPresent()) return opt.get().getId();
        Category created = Category.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .name(c)
                .color(null)
                .build();
        categoryRepository.save(created);
        return created.getId();
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
