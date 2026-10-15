package club.badernet.nestwerk.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ChoreCreditRepository extends JpaRepository<ChoreCredit, Long> {

    List<ChoreCredit> findAllByTaskId(Long taskId);

    List<ChoreCredit> findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    List<ChoreCredit> findAllByMemberId(Long memberId);

    boolean existsByTaskChoreId(Long choreId);
}
