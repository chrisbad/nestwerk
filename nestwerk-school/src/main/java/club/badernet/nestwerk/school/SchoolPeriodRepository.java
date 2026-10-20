package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchoolPeriodRepository extends JpaRepository<SchoolPeriod, Long> {

    List<SchoolPeriod> findAllBySchoolContactIdOrderByStartsAtAsc(Long schoolContactId);

    void deleteAllBySchoolContactId(Long schoolContactId);
}
