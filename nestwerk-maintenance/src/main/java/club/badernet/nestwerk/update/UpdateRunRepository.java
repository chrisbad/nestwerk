package club.badernet.nestwerk.update;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UpdateRunRepository extends JpaRepository<UpdateRun, Long> {

    List<UpdateRun> findAllByOrderByRequestedAtDescIdDesc(Pageable page);

    List<UpdateRun> findByStatusIn(Collection<UpdateRun.Status> statuses);

    Optional<UpdateRun> findByJobId(String jobId);
}
