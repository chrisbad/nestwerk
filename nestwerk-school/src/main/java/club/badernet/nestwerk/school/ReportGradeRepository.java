package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReportGradeRepository extends JpaRepository<ReportGrade, Long> {

    Optional<ReportGrade> findBySubjectIdAndTerm(Long subjectId, int term);

    List<ReportGrade> findAllBySubjectIdInAndTerm(Collection<Long> subjectIds, int term);
}
