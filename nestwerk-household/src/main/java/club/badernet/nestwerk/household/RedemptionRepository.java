package club.badernet.nestwerk.household;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    boolean existsByRewardId(Long rewardId);

    boolean existsByRewardIdAndStatus(Long rewardId, RedemptionStatus status);

    List<Redemption> findAllByStatusOrderByRequestedAtAsc(RedemptionStatus status);

    List<Redemption> findAllByOrderByRequestedAtDescIdDesc(Pageable page);

    List<Redemption> findAllByMemberIdOrderByRequestedAtDescIdDesc(Long memberId, Pageable page);
}
