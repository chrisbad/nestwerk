package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.Occurrence;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Aufbereitete Angaben für die Lese-Ansicht eines Termins. */
public record EventDetails(Occurrence occurrence, String when, String repeat) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMANY);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY);
    private static final Map<String, String> RULES = Map.of(
            "FREQ=DAILY", "Täglich",
            "FREQ=WEEKLY", "Wöchentlich",
            "FREQ=WEEKLY;INTERVAL=2", "Alle 2 Wochen",
            "FREQ=MONTHLY", "Monatlich",
            "FREQ=YEARLY", "Jährlich");

    public static EventDetails of(Occurrence occurrence, String rrule) {
        return new EventDetails(occurrence, when(occurrence), repeat(rrule));
    }

    static String when(Occurrence o) {
        ZonedDateTime start = o.start();
        ZonedDateTime end = o.end();
        if (o.allDay()) {
            LocalDate lastDay = end.toLocalDate().minusDays(1);
            if (!lastDay.isAfter(start.toLocalDate())) {
                return DAY.format(start) + ", ganztägig";
            }
            return DAY.format(start) + " bis " + DAY.format(lastDay) + ", ganztägig";
        }
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return DAY.format(start) + ", " + TIME.format(start) + "–" + TIME.format(end) + " Uhr";
        }
        return DAY.format(start) + ", " + TIME.format(start) + " Uhr bis "
                + DAY.format(end) + ", " + TIME.format(end) + " Uhr";
    }

    static String repeat(String rrule) {
        if (rrule == null) {
            return null;
        }
        return RULES.getOrDefault(rrule, "Eigene Regel: " + rrule);
    }
}
