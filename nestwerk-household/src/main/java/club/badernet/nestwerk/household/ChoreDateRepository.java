package club.badernet.nestwerk.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChoreDateRepository extends JpaRepository<ChoreDate, Long> {

    List<ChoreDate> findAllByChoreIdOrderByDueOnAscDueTimeAsc(Long choreId);
}
