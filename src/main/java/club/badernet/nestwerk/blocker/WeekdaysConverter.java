package club.badernet.nestwerk.blocker;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;

/** Wochentage als Bitmaske: Montag = 1, Dienstag = 2, … Sonntag = 64. */
@Converter
public class WeekdaysConverter implements AttributeConverter<Set<DayOfWeek>, Short> {

    @Override
    public Short convertToDatabaseColumn(Set<DayOfWeek> days) {
        int mask = 0;
        for (DayOfWeek day : days) {
            mask |= 1 << (day.getValue() - 1);
        }
        return (short) mask;
    }

    @Override
    public Set<DayOfWeek> convertToEntityAttribute(Short mask) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (mask != null && (mask & (1 << (day.getValue() - 1))) != 0) {
                days.add(day);
            }
        }
        return days;
    }
}
