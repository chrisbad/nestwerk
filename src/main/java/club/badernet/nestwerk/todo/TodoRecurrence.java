package club.badernet.nestwerk.todo;

import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/** Wiederholungsregeln (iCal-RRULE) für ToDos; die Berechnung übernimmt ical4j. */
@Component
public class TodoRecurrence {

    /** Normalisiert die Regel ({@code RRULE:}-Präfix entfernen) und prüft sie; leer → {@code null}. */
    public String normalize(String rrule, LocalDate seed) {
        if (rrule == null || rrule.isBlank()) {
            return null;
        }
        String rule = rrule.trim().replaceFirst("(?i)^RRULE:", "");
        if (rule.length() > 500) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist zu lang.");
        }
        try {
            new Recur<LocalDate>(rule).getDates(seed, seed, seed.plusYears(2));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist ungültig.", e);
        }
        return rule;
    }

    /** Erstes Vorkommen der Serie (Beginn {@code seed}) nach {@code after}; leer, wenn die Regel erschöpft ist. */
    public Optional<LocalDate> next(String rrule, LocalDate seed, LocalDate after) {
        Recur<LocalDate> recur = new Recur<>(rrule);
        LocalDate from = after.plusDays(1);
        return recur.getDates(seed, from.isBefore(seed) ? seed : from, after.plusYears(10)).stream()
                .filter(d -> d.isAfter(after))
                .sorted()
                .findFirst();
    }
}
