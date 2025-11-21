package cn.bitsleep.tdl.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "todo_tag")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(TodoTag.TodoTagId.class)
public class TodoTag {

    @Id
    @Column(name = "todo_id", nullable = false)
    private String todoId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private String tagId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodoTagId implements java.io.Serializable {
        private String todoId;
        private String tagId;
    }
}
