package club.badernet.nestwerk.blocker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestGoalRepository extends JpaRepository<RestGoal, Long> {

    List<RestGoal> findAllByMemberId(Long memberId);
}
