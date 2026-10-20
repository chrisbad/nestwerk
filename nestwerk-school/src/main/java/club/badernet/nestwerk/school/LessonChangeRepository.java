package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LessonChangeRepository extends JpaRepository<LessonChange, Long> {

    Optional<LessonChange> findByLessonIdAndDay(Long lessonId, LocalDate day);

    @Query("SELECT c FROM LessonChange c WHERE c.lessonId IN :lessonIds AND c.day BETWEEN :from AND :to")
    List<LessonChange> findInRange(Collection<Long> lessonIds, LocalDate from, LocalDate to);
}
