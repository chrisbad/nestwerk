package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SchoolLetterRepository extends JpaRepository<SchoolLetter, Long> {

    List<SchoolLetter> findAllByYearId(Long yearId);

    List<SchoolLetter> findAllBySignedAtIsNullAndDueOnGreaterThanEqual(LocalDate day);
}
