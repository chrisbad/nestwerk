package club.badernet.nestwerk.todo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoLinkRepository extends JpaRepository<TodoLink, Long> {

    List<TodoLink> findAllBySourceIdOrTargetId(Long sourceId, Long targetId);

    List<TodoLink> findAllByTargetIdAndType(Long targetId, TodoLinkType type);

    List<TodoLink> findAllBySourceIdAndType(Long sourceId, TodoLinkType type);

    boolean existsBySourceIdAndTargetIdAndType(Long sourceId, Long targetId, TodoLinkType type);
}
