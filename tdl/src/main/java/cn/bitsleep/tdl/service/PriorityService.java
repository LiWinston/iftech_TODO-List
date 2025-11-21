package cn.bitsleep.tdl.service;

import cn.bitsleep.tdl.domain.PriorityLevel;
import cn.bitsleep.tdl.repo.PriorityLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PriorityService {

    private final PriorityLevelRepository repo;

    private static final long INITIAL_GAP = 1_000_000L; // 初始间隔
    private static final long MIN_GAP = 10L;            // 小于此 gap 触发重排

    public List<PriorityLevel> list(String userId) {
        return repo.findByUserIdOrderByRankAsc(userId);
    }

    @Transactional
    public PriorityLevel create(String userId, String name, String afterId, String beforeId) {
        // 加锁获取全部（FOR UPDATE）避免并发 rank 冲突
        List<PriorityLevel> all = repo.findAllForUpdate(userId);
        long newRank;
        if (all.isEmpty()) {
            newRank = 1_000_000_000L; // 第一条给一个更大的可扩展空间
        } else if (afterId == null && beforeId == null) {
            // 追加到末尾
            newRank = all.get(all.size() - 1).getRank() + INITIAL_GAP;
        } else {
            PriorityLevel after = null, before = null;
            if (afterId != null) after = all.stream().filter(p -> p.getId().equals(afterId)).findFirst().orElse(null);
            if (beforeId != null) before = all.stream().filter(p -> p.getId().equals(beforeId)).findFirst().orElse(null);
            if (after == null && before == null) {
                newRank = all.get(all.size() - 1).getRank() + INITIAL_GAP;
            } else if (after != null && before == null) {
                newRank = after.getRank() + INITIAL_GAP;
            } else if (after == null) { // before != null
                newRank = before.getRank() / 2; // 插到最前面
            } else { // 两者都在，取中间
                long a = after.getRank();
                long b = before.getRank();
                if (b <= a) { // 异常顺序，直接靠后插入
                    newRank = a + INITIAL_GAP;
                } else {
                    long gap = b - a;
                    if (gap <= MIN_GAP) {
                        // 触发重排：重新分配 rank
                        long base = 1_000_000_000L;
                        for (int i = 0; i < all.size(); i++) {
                            all.get(i).setRank(base + i * INITIAL_GAP);
                        }
                        repo.saveAll(all);
                        // 重新计算 after/before rank
                        a = after.getRank();
                        b = before.getRank();
                    }
                    newRank = a + (b - a) / 2;
                }
            }
        }
        PriorityLevel pl = PriorityLevel.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .name(name)
                .rank(newRank)
                .build();
        return repo.save(pl);
    }

    @Transactional
    public void rename(String id, String userId, String newName) {
        PriorityLevel pl = repo.findById(id).orElseThrow();
        if (!pl.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        pl.setName(newName);
        repo.save(pl);
    }

    @Transactional
    public void moveBetween(String id, String userId, String afterId, String beforeId) {
        PriorityLevel pl = repo.findById(id).orElseThrow();
        if (!pl.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        // 简化：删除再按新位置重建 rank（保持 id 不变）
        List<PriorityLevel> all = repo.findAllForUpdate(userId);
        all.removeIf(p -> p.getId().equals(id));
        // 计算新 rank
        long newRank;
        if (all.isEmpty()) newRank = 1_000_000_000L;
        else if (afterId == null && beforeId == null) newRank = all.get(all.size()-1).getRank() + INITIAL_GAP;
        else {
            PriorityLevel after = null, before = null;
            if (afterId != null) after = all.stream().filter(p -> p.getId().equals(afterId)).findFirst().orElse(null);
            if (beforeId != null) before = all.stream().filter(p -> p.getId().equals(beforeId)).findFirst().orElse(null);
            if (after == null && before == null) newRank = all.get(all.size()-1).getRank() + INITIAL_GAP;
            else if (after != null && before == null) newRank = after.getRank() + INITIAL_GAP;
            else if (after == null) newRank = before.getRank() / 2;
            else {
                long a = after.getRank();
                long b = before.getRank();
                long gap = b - a;
                if (gap <= MIN_GAP) {
                    long base = 1_000_000_000L;
                    for (int i = 0; i < all.size(); i++) {
                        all.get(i).setRank(base + i * INITIAL_GAP);
                    }
                    repo.saveAll(all);
                    a = after.getRank();
                    b = before.getRank();
                }
                newRank = a + (b - a)/2;
            }
        }
        pl.setRank(newRank);
        repo.save(pl);
    }

    @Transactional
    public void delete(String id, String userId) {
        PriorityLevel pl = repo.findById(id).orElseThrow();
        if (!pl.getUserId().equals(userId)) throw new IllegalArgumentException("Forbidden");
        repo.delete(pl); // 任务上引用保持 NULL 或保留旧 label，不强制级联
    }
}
