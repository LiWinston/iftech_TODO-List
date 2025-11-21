package cn.bitsleep.tdl.repo;

import cn.bitsleep.tdl.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {
    List<Category> findByUserIdOrderByNameAsc(String userId);
    Optional<Category> findByUserIdAndName(String userId, String name);
}
