package cn.bitsleep.tdl.repo;

import cn.bitsleep.tdl.domain.TodoTag;
import cn.bitsleep.tdl.domain.TodoTag.TodoTagId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoTagRepository extends JpaRepository<TodoTag, TodoTagId> {
    List<TodoTag> findByTodoId(String todoId);

    @Modifying
    @Query(value = "DELETE FROM todo_tag WHERE todo_id = :todoId", nativeQuery = true)
    int deleteByTodoId(@Param("todoId") String todoId);

    @Modifying
    @Query(value = "DELETE FROM todo_tag WHERE tag_id = :tagId", nativeQuery = true)
    int deleteByTagId(@Param("tagId") String tagId);
}
