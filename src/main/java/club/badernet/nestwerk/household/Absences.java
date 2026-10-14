package club.badernet.nestwerk.household;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.RecurrenceExpander;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Abwesenheiten der Mitglieder aus Kalenderterminen mit dem Häkchen „abwesend“. */
@Component
public class Absences {

    /** Abwesende Tage je Mitglied. */
    public record AbsenceMap(Map<Long, Set<LocalDate>> days) {

        public boolean isAbsent(Long memberId, LocalDate day) {
            return days.getOrDefault(memberId, Set.of()).contains(day);
        }
    }

    private final CalendarEventRepository events;
    private final RecurrenceExpander expander;
    private final ZoneId zone;

    public Absences(CalendarEventRepository events, RecurrenceExpander expander, CalendarSettings settings) {
        this.events = events;
        this.expander = expander;
        this.zone = settings.zone();
    }

    /** Abwesenheiten von {@code from} bis {@code to} (jeweils einschließlich). */
    @Transactional(readOnly = true)
    public AbsenceMap between(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Map<Long, Set<LocalDate>> days = new HashMap<>();
        for (CalendarEvent event : events.findAllByAbsenceTrue()) {
            if (event.getMemberIds().isEmpty()) {
                continue;
            }
            for (Occurrence o : expander.expand(event, start, end)) {
                LocalDate first = o.start().withZoneSameInstant(zone).toLocalDate();
                LocalDate last = o.end().withZoneSameInstant(zone).minusNanos(1).toLocalDate();
                for (LocalDate d = first.isBefore(from) ? from : first; !d.isAfter(last) && !d.isAfter(to);
                     d = d.plusDays(1)) {
                    for (Long member : event.getMemberIds()) {
                        days.computeIfAbsent(member, k -> new HashSet<>()).add(d);
                    }
                }
            }
        }
        return new AbsenceMap(days);
    }
}
