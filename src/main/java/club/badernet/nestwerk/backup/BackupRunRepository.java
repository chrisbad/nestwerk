package club.badernet.nestwerk.backup;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    List<BackupRun> findAllByOrderByStartedAtDescIdDesc(Pageable page);

    Optional<BackupRun> findFirstByKindInAndStatusInOrderByStartedAtDesc(List<BackupRun.Kind> kinds,
                                                                         List<BackupRun.Status> statuses);
}
