package club.badernet.nestwerk.school;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface SchoolHolidayRepository extends JpaRepository<SchoolHoliday, Long> {

    @Query("SELECT h FROM SchoolHoliday h WHERE h.state = :state AND h.endsOn >= :from AND h.startsOn <= :to "
            + "ORDER BY h.startsOn")
    List<SchoolHoliday> findOverlapping(String state, LocalDate from, LocalDate to);

    @Modifying
    @Query("DELETE FROM SchoolHoliday h WHERE h.state = :state AND h.source = 'ONLINE' AND h.endsOn >= :from")
    void deleteOnlineFrom(String state, LocalDate from);
}
