package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.GermanHolidays;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Ob an einem Tag schulfrei ist (Feiertage, später auch Schulferien). */
@Component
public class SchoolDays {

    public boolean isFree(LocalDate day, String state) {
        return GermanHolidays.isHoliday(day, state);
    }
}
