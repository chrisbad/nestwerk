package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Long> {

    List<SchoolExam> findAllByYearIdOrderByDayAscIdAsc(Long yearId);

    List<SchoolExam> findAllByDayGreaterThanEqual(LocalDate day);
}
