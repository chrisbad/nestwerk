package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchoolGradeRepository extends JpaRepository<SchoolGrade, Long> {

    List<SchoolGrade> findAllByYearIdAndTermOrderByDayAscIdAsc(Long yearId, int term);
}
