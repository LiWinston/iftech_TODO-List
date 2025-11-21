package cn.bitsleep.tdl.service;

import cn.bitsleep.tdl.domain.Tag;
import cn.bitsleep.tdl.domain.TodoTag;
import cn.bitsleep.tdl.repo.TagRepository;
import cn.bitsleep.tdl.repo.TodoTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TagService {
    private final TagRepository tagRepo;
    private final TodoTagRepository todoTagRepo;

    public List<Tag> list(String userId) { return tagRepo.findByUserIdOrderByNameAsc(userId); }

    @Transactional
    public Tag create(String userId, String name) {
        Tag tag = Tag.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .name(name)
                .build();
        return tagRepo.save(tag);
    }

    @Transactional
    public void rename(String id, String userId, String newName) {
        Tag tag = tagRepo.findById(id).orElseThrow();
        if (!tag.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        tag.setName(newName);
        tagRepo.save(tag);
    }

    @Transactional
    public void delete(String id, String userId) {
        Tag tag = tagRepo.findById(id).orElseThrow();
        if (!tag.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        // 删除标签及其所有关联
        todoTagRepo.deleteByTagId(id);
        tagRepo.delete(tag);
    }

    @Transactional
    public void setTagsForTodo(String todoId, String userId, List<String> tagIds) {
        // 简化：先删再插
        todoTagRepo.deleteByTodoId(todoId);
        for (String tagId : tagIds) {
            TodoTag tt = TodoTag.builder().todoId(todoId).tagId(tagId).build();
            todoTagRepo.save(tt);
        }
    }
}
