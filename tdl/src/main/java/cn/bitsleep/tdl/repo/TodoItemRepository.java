package cn.bitsleep.tdl.repo;

import cn.bitsleep.tdl.domain.TodoItem;
import cn.bitsleep.tdl.domain.TodoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface TodoItemRepository extends JpaRepository<TodoItem, String> {

    @Query(value = """
            SELECT * FROM todo_item
            WHERE user_id = :userId
            AND status = ANY(:statusCodes)
            AND (
                CAST(:cursorCreatedAt AS TIMESTAMP) IS NULL
                OR created_at < CAST(:cursorCreatedAt AS TIMESTAMP)
                OR (created_at = CAST(:cursorCreatedAt AS TIMESTAMP) AND id < :cursorId)
            )
            ORDER BY created_at DESC, id DESC
            LIMIT :size
            """, nativeQuery = true)
    List<TodoItem> keysetPage(@Param("userId") String userId,
                              @Param("statusCodes") Short[] statusCodes,
                              @Param("cursorCreatedAt") Instant cursorCreatedAt,
                              @Param("cursorId") String cursorId,
                              @Param("size") int size);

    @Query(value = """
            SELECT * FROM todo_item
            WHERE user_id = :userId
            AND status = ANY(:statusCodes)
            ORDER BY created_at DESC, id DESC
            LIMIT :size
            """, nativeQuery = true)
    List<TodoItem> firstPage(@Param("userId") String userId,
                             @Param("statusCodes") Short[] statusCodes,
                             @Param("size") int size);

    @Modifying
    @Query(value = """
            UPDATE todo_item
            SET status = :statusCode,
                trash_purge_at = :purgeAt,
                version = version + 1
            WHERE id = :id AND user_id = :userId
            """,
            nativeQuery = true)
    int updateStatus(@Param("id") String id,
                     @Param("userId") String userId,
                     @Param("statusCode") int statusCode,
                     @Param("purgeAt") Instant purgeAt);

    @Modifying
    @Query(value = "DELETE FROM todo_item WHERE id = :id AND user_id = :userId", nativeQuery = true)
    int hardDelete(@Param("id") String id, @Param("userId") String userId);

    @Modifying
    @Query(value = """
            UPDATE todo_item SET
              title = :title,
              description = :description,
              priority_score = :priorityScore,
              priority_label = :priorityLabel,
              category_id = :categoryId,
              text = :text,
              metadata = CAST(:metadata AS jsonb),
              version = version + 1
            WHERE id = :id AND user_id = :userId
            """,
            nativeQuery = true)
    int updateContent(@Param("id") String id,
                      @Param("userId") String userId,
                      @Param("title") String title,
                      @Param("description") String description,
                      @Param("priorityScore") BigDecimal priorityScore,
                      @Param("priorityLabel") String priorityLabel,
                      @Param("categoryId") String categoryId,
                      @Param("text") String text,
                      @Param("metadata") String metadata);

    @Modifying
    @Query(value = """
            UPDATE todo_item SET
              embedding = CAST(:vec AS vector),
              text = :text,
                                                        metadata = CAST(:metadata AS jsonb),
                                                        version = version + 1
            WHERE id = :id AND user_id = :userId
            """, nativeQuery = true)
    int updateEmbedding(@Param("id") String id,
                        @Param("userId") String userId,
                        @Param("vec") String vectorLiteral,
                        @Param("text") String text,
                        @Param("metadata") String metadata);

                @Query(value = """
                                                SELECT
                                                        id,
                                                        (
                                                                -- Title exact / prefix / contains boosts
                                                                (CASE WHEN title ILIKE :q THEN 0.6
                                                                                        WHEN title ILIKE (:q || '%') THEN 0.5
                                                                                        WHEN title ILIKE ('%' || :q || '%') THEN 0.4
                                                                                        ELSE 0 END)
                                                                +
                                                                -- Description exact / prefix / contains boosts
                                                                (CASE WHEN coalesce(description,'') ILIKE :q THEN 0.3
                                                                                        WHEN coalesce(description,'') ILIKE (:q || '%') THEN 0.2
                                                                                        WHEN coalesce(description,'') ILIKE ('%' || :q || '%') THEN 0.1
                                                                                        ELSE 0 END)
                                                                +
                                                                -- Trigram similarities (scaled small, just for tie-breaker)
                                                                0.20 * similarity(title, :q)
                                                                + 0.10 * similarity(coalesce(description,''), :q)
                                                        ) AS score,
                                                        (title ILIKE :q) AS title_exact,
                                                        (title ILIKE (:q || '%')) AS title_prefix
                                                FROM todo_item
                                                WHERE user_id = :userId AND status != 2
                                                        AND (
                                                                title ILIKE ('%' || :q || '%')
                                                                OR coalesce(description,'') ILIKE ('%' || :q || '%')
                                                        )
                                                ORDER BY score DESC
                                                LIMIT :n
                                                """, nativeQuery = true)
                List<Object[]> textSearch(@Param("userId") String userId,
                                                                                                                        @Param("q") String query,
                                                                                                                        @Param("n") int limit);
}
