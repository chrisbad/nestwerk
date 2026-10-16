package club.badernet.nestwerk.blocker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BlockerRepository extends JpaRepository<Blocker, Long> {

    @Query("SELECT b FROM Blocker b JOIN b.memberIds m WHERE m = :memberId ORDER BY b.startTime")
    List<Blocker> findAllByMemberId(Long memberId);

    List<Blocker> findAllByOrderByTitle();
}
