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
              AND status IN (:statusCodes)
              AND (
                    (:cursorCreatedAt IS NULL AND :cursorId IS NULL)
                 OR (created_at < :cursorCreatedAt)
                 OR (created_at = :cursorCreatedAt AND id < :cursorId)
              )
            ORDER BY created_at DESC, id DESC
            LIMIT :size
            """,
            nativeQuery = true)
    List<TodoItem> keysetPage(
            @Param("userId") String userId,
            @Param("statusCodes") List<Integer> statusCodes,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") String cursorId,
            @Param("size") int size
    );

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
}
