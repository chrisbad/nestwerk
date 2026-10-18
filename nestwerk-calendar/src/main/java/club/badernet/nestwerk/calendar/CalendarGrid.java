package club.badernet.nestwerk.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CalendarGrid {

    private CalendarGrid() {
    }

    public static List<LocalDate> monthDays(YearMonth month) {
        LocalDate start = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return start.datesUntil(end.plusDays(1)).toList();
    }

    public static List<LocalDate> weekDays(LocalDate any) {
        LocalDate monday = any.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.datesUntil(monday.plusDays(7)).toList();
    }

    public static Map<LocalDate, List<Occurrence>> groupByDay(List<Occurrence> occurrences,
                                                              List<LocalDate> days) {
        Map<LocalDate, List<Occurrence>> grouped = new LinkedHashMap<>();
        days.forEach(d -> grouped.put(d, new ArrayList<>()));
        for (Occurrence o : occurrences) {
            LocalDate first = o.start().toLocalDate();
            LocalDate last = o.end().minusNanos(1).toLocalDate();
            for (LocalDate day : days) {
                if (!day.isBefore(first) && !day.isAfter(last)) {
                    grouped.get(day).add(o);
                }
            }
        }
        grouped.values().forEach(l -> l.sort(Comparator.comparing(Occurrence::start)));
        return grouped;
    }
}
