package club.badernet.nestwerk.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    Optional<CalendarEvent> findByUid(String uid);

    List<CalendarEvent> findAllByAbsenceTrue();

    /**
     * Kandidaten für ein Zeitfenster: Einzeltermine, die das Fenster berühren, und alle Serien,
     * die vor Fensterende beginnen. Private Termine anderer Benutzer sind bereits herausgefiltert.
     */
    @Query("""
            select distinct e from CalendarEvent e
            where e.startsAt < :to
              and (e.rrule is not null or e.endsAt > :from)
              and (e.privateEvent = false or e.createdBy = :viewerId or :admin = true)
            """)
    List<CalendarEvent> findCandidates(@Param("from") Instant from, @Param("to") Instant to,
                                       @Param("viewerId") Long viewerId, @Param("admin") boolean admin);
}
