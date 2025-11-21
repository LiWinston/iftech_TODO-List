package cn.bitsleep.tdl.service;

import cn.bitsleep.tdl.domain.Category;
import cn.bitsleep.tdl.repo.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {
    private final CategoryRepository repo;

    public List<Category> list(String userId) { return repo.findByUserIdOrderByNameAsc(userId); }

    @Transactional
    public Category create(String userId, String name, String color) {
        Category c = Category.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .name(name)
                .color(color)
                .build();
        return repo.save(c);
    }

    @Transactional
    public void rename(String id, String userId, String newName, String color) {
        Category c = repo.findById(id).orElseThrow();
        if (!c.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        c.setName(newName);
        c.setColor(color);
        repo.save(c);
    }

    @Transactional
    public void delete(String id, String userId) {
        Category c = repo.findById(id).orElseThrow();
        if (!c.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        repo.delete(c); // todo_item.category_id 将被置空
    }
}
