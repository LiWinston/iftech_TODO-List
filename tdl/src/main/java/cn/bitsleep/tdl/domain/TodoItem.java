package cn.bitsleep.tdl.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "todo_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id; // UUID as string

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "priority_score", nullable = false)
    private BigDecimal priorityScore;

    @Column(name = "priority_label")
    private String priorityLabel;

    @Column(name = "category_id")
    private String categoryId;

    @Column(name = "status", nullable = false)
    private Integer statusCode; // map enum manually

    @Column(name = "trash_purge_at")
    private Instant trashPurgeAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Columns reserved by PgVectorEmbeddingStore
    @Column(name = "text")
    private String embeddingText;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    public TodoStatus getStatus() { return TodoStatus.fromCode(statusCode); }
    public void setStatus(TodoStatus s) { this.statusCode = s.code; }
}
