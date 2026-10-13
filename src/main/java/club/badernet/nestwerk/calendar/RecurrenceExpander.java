package club.badernet.nestwerk.calendar;

import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Component
public class RecurrenceExpander {

    public void validate(String rrule, ZonedDateTime seed) {
        try {
            new Recur<ZonedDateTime>(rrule).getDates(seed, seed, seed.plusYears(2));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist ungültig.", e);
        }
    }

    /** Beginne laut Regel (ohne Ausnahmen) im Fenster; Fenstergrenzen inklusive. */
    public List<Instant> baseStarts(CalendarEvent event, Instant from, Instant to) {
        Set<Instant> starts = new TreeSet<>();
        Instant seedInstant = event.getStartsAt();
        if (event.getRrule() == null) {
            if (!seedInstant.isBefore(from) && !seedInstant.isAfter(to)) {
                starts.add(seedInstant);
            }
        } else if (!to.isBefore(seedInstant)) {
            ZoneId zone = ZoneId.of(event.getTimezone());
            ZonedDateTime seed = seedInstant.atZone(zone);
            new Recur<ZonedDateTime>(event.getRrule())
                    .getDates(seed, from.atZone(zone), to.atZone(zone))
                    .forEach(d -> starts.add(d.toInstant()));
            if (!seedInstant.isBefore(from) && !seedInstant.isAfter(to)) {
                starts.add(seedInstant);
            }
        }
        return List.copyOf(starts);
    }

    public boolean isBaseOccurrence(CalendarEvent event, Instant start) {
        return baseStarts(event, start.minusSeconds(1), start.plusSeconds(1)).contains(start);
    }

    public List<Occurrence> expand(CalendarEvent event, Instant from, Instant to) {
        ZoneId zone = ZoneId.of(event.getTimezone());
        Duration length = Duration.between(event.getStartsAt(), event.getEndsAt());
        List<Occurrence> result = new ArrayList<>();
        Set<Instant> originals = new TreeSet<>(baseStarts(event, from.minus(length), to));
        // Vorkommen, die aus einem anderen Zeitraum in dieses Fenster verschoben wurden
        for (CalendarEventException ex : event.getExceptions()) {
            if (!ex.isCancelled() && ex.getStartsAt() != null && ex.getStartsAt().isBefore(to)
                    && isBaseOccurrence(event, ex.getRecurrenceId())) {
                originals.add(ex.getRecurrenceId());
            }
        }
        for (Instant original : originals) {
            Optional<CalendarEventException> exception = event.exceptionFor(original);
            if (exception.isPresent() && exception.get().isCancelled()) {
                continue;
            }
            Instant start = original;
            Instant end = original.plus(length);
            String title = event.getTitle();
            String description = event.getDescription();
            String location = event.getLocation();
            if (exception.isPresent()) {
                CalendarEventException ex = exception.get();
                if (ex.getStartsAt() != null) {
                    start = ex.getStartsAt();
                    end = start.plus(length);
                }
                if (ex.getEndsAt() != null) {
                    end = ex.getEndsAt();
                }
                title = ex.getTitle() != null ? ex.getTitle() : title;
                description = ex.getDescription() != null ? ex.getDescription() : description;
                location = ex.getLocation() != null ? ex.getLocation() : location;
            }
            if (end.isAfter(from) && start.isBefore(to)) {
                result.add(new Occurrence(event.getId(), event.getUid(), original, title, description,
                        location, start.atZone(zone), end.atZone(zone), event.isAllDay(), event.isPrivateEvent(),
                        event.getRrule() != null, event.getCreatedBy(), event.getMemberIds()));
            }
        }
        result.sort(Comparator.comparing(Occurrence::start));
        return result;
    }
}
