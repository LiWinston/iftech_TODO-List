package cn.bitsleep.tdl.repo;

import cn.bitsleep.tdl.domain.PriorityLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PriorityLevelRepository extends JpaRepository<PriorityLevel, String> {
    List<PriorityLevel> findByUserIdOrderByRankAsc(String userId);
    Optional<PriorityLevel> findByUserIdAndName(String userId, String name);

    @Query(value = "SELECT * FROM priority_level WHERE user_id = :userId ORDER BY rank ASC FOR UPDATE", nativeQuery = true)
    List<PriorityLevel> findAllForUpdate(String userId);
}
