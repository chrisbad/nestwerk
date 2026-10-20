package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.GermanHolidays;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Ob an einem Tag schulfrei ist: Schulferien des Bundeslands und gesetzliche Feiertage. */
@Component
public class SchoolDays {

    private final SchoolHolidayRepository holidays;

    public SchoolDays(SchoolHolidayRepository holidays) {
        this.holidays = holidays;
    }

    /** Alle schulfreien Tage im Zeitraum mit ihrem Grund (Ferienname oder „Feiertag“). */
    @Transactional(readOnly = true)
    public Map<LocalDate, String> freeDays(String state, LocalDate from, LocalDate to) {
        Map<LocalDate, String> result = new TreeMap<>();
        if (state == null || state.isBlank()) {
            return result;
        }
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            for (LocalDate day : GermanHolidays.holidays(year, state)) {
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    result.put(day, "Feiertag");
                }
            }
        }
        // Ferien gehen vor: sie nennen den genaueren Grund
        for (SchoolHoliday holiday : holidays.findOverlapping(state, from, to)) {
            for (LocalDate day = holiday.getStartsOn(); !day.isAfter(holiday.getEndsOn()); day = day.plusDays(1)) {
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    result.put(day, holiday.getName());
                }
            }
        }
        return result;
    }

    public Optional<String> reason(LocalDate day, String state) {
        return Optional.ofNullable(freeDays(state, day, day).get(day));
    }

    public boolean isFree(LocalDate day, String state) {
        return reason(day, state).isPresent();
    }
}
