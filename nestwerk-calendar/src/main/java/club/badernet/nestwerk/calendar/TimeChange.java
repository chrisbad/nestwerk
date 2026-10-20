package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.blocker.TimeRange;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** New times of an event or occurrence in local time of the app zone: with a time of day, or whole days. */
public sealed interface TimeChange {

    /** The instants in {@code zone}; fails if the end is not after the start. */
    TimeRange in(ZoneId zone);

    boolean allDay();

    /** Start and end with time of day; {@code end} is exclusive. */
    record Timed(LocalDateTime start, LocalDateTime end) implements TimeChange {

        @Override
        public TimeRange in(ZoneId zone) {
            if (start == null || end == null) {
                throw new IllegalArgumentException("Beginn und Ende sind erforderlich.");
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("Das Ende muss nach dem Beginn liegen.");
            }
            return new TimeRange(start.atZone(zone).toInstant(), end.atZone(zone).toInstant());
        }

        @Override
        public boolean allDay() {
            return false;
        }
    }

    /** Whole days; {@code lastDay} is inclusive. */
    record AllDay(LocalDate firstDay, LocalDate lastDay) implements TimeChange {

        @Override
        public TimeRange in(ZoneId zone) {
            if (firstDay == null || lastDay == null) {
                throw new IllegalArgumentException("Start- und Enddatum sind erforderlich.");
            }
            if (lastDay.isBefore(firstDay)) {
                throw new IllegalArgumentException("Das Enddatum darf nicht vor dem Startdatum liegen.");
            }
            return new TimeRange(firstDay.atStartOfDay(zone).toInstant(),
                    lastDay.plusDays(1).atStartOfDay(zone).toInstant());
        }

        @Override
        public boolean allDay() {
            return true;
        }
    }
}
