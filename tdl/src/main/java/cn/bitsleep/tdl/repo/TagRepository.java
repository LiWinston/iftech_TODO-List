package cn.bitsleep.tdl.repo;

import cn.bitsleep.tdl.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, String> {
    List<Tag> findByUserIdOrderByNameAsc(String userId);
    Optional<Tag> findByUserIdAndName(String userId, String name);
}
