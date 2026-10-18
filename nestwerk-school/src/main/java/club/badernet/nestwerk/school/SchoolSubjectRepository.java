package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchoolSubjectRepository extends JpaRepository<SchoolSubject, Long> {

    List<SchoolSubject> findAllByYearIdOrderByNameAsc(Long yearId);

    boolean existsByYearIdAndNameIgnoreCase(Long yearId, String name);
}
