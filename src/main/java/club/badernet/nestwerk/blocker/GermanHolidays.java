package club.badernet.nestwerk.blocker;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gesetzliche Feiertage je Bundesland (landesweit geltende; nur örtlich geltende wie Mariä Himmelfahrt in Teilen
 * Bayerns oder das Augsburger Friedensfest sind nicht enthalten).
 */
public final class GermanHolidays {

    public static final Map<String, String> STATES;

    static {
        Map<String, String> states = new LinkedHashMap<>();
        states.put("BW", "Baden-Württemberg");
        states.put("BY", "Bayern");
        states.put("BE", "Berlin");
        states.put("BB", "Brandenburg");
        states.put("HB", "Bremen");
        states.put("HH", "Hamburg");
        states.put("HE", "Hessen");
        states.put("MV", "Mecklenburg-Vorpommern");
        states.put("NI", "Niedersachsen");
        states.put("NW", "Nordrhein-Westfalen");
        states.put("RP", "Rheinland-Pfalz");
        states.put("SL", "Saarland");
        states.put("SN", "Sachsen");
        states.put("ST", "Sachsen-Anhalt");
        states.put("SH", "Schleswig-Holstein");
        states.put("TH", "Thüringen");
        STATES = Map.copyOf(states);
    }

    private GermanHolidays() {
    }

    public static boolean isHoliday(LocalDate day, String state) {
        return state != null && !state.isBlank() && holidays(day.getYear(), state).contains(day);
    }

    public static Set<LocalDate> holidays(int year, String state) {
        Set<LocalDate> days = new TreeSet<>();
        if (state == null || !STATES.containsKey(state)) {
            return days;
        }
        LocalDate easter = easterSunday(year);
        days.add(LocalDate.of(year, 1, 1));
        days.add(easter.minusDays(2));
        days.add(easter.plusDays(1));
        days.add(LocalDate.of(year, 5, 1));
        days.add(easter.plusDays(39));
        days.add(easter.plusDays(50));
        days.add(LocalDate.of(year, 10, 3));
        days.add(LocalDate.of(year, 12, 25));
        days.add(LocalDate.of(year, 12, 26));
        if (in(state, "BW", "BY", "ST")) {
            days.add(LocalDate.of(year, 1, 6));
        }
        if (in(state, "BE", "MV")) {
            days.add(LocalDate.of(year, 3, 8));
        }
        if (in(state, "BB")) {
            days.add(easter);
            days.add(easter.plusDays(49));
        }
        if (in(state, "BW", "BY", "HE", "NW", "RP", "SL")) {
            days.add(easter.plusDays(60));
        }
        if (in(state, "SL")) {
            days.add(LocalDate.of(year, 8, 15));
        }
        if (in(state, "TH")) {
            days.add(LocalDate.of(year, 9, 20));
        }
        if (in(state, "BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH")) {
            days.add(LocalDate.of(year, 10, 31));
        }
        if (in(state, "BW", "BY", "NW", "RP", "SL")) {
            days.add(LocalDate.of(year, 11, 1));
        }
        if (in(state, "SN")) {
            // Buß- und Bettag: Mittwoch vor dem 23. November
            days.add(LocalDate.of(year, 11, 22).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY)));
        }
        return days;
    }

    /** Ostersonntag nach der Gaußschen Osterformel (gregorianisch, anonymer Algorithmus). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    private static boolean in(String state, String... states) {
        for (String s : states) {
            if (s.equals(state)) {
                return true;
            }
        }
        return false;
    }
}
