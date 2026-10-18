package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolYearRepository extends JpaRepository<SchoolYear, Long> {

    List<SchoolYear> findAllByProfileIdOrderByStartsOnDesc(Long profileId);

    Optional<SchoolYear> findByProfileIdAndActiveTrue(Long profileId);
}
