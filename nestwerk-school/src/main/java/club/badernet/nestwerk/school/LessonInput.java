package club.badernet.nestwerk.school;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Input for a timetable lesson: either placed in the school's period grid ({@code periodFrom}–{@code periodTo}, the
 * times then come from the grid) or with free times ({@code startsAt}–{@code endsAt}).
 */
public record LessonInput(Long subjectId, DayOfWeek weekday, Integer periodFrom, Integer periodTo,
                          LocalTime startsAt, LocalTime endsAt, String room, WeekKind weekKind) {

    /** A lesson with free times. */
    public static LessonInput timed(Long subjectId, DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt,
                                    String room, WeekKind weekKind) {
        return new LessonInput(subjectId, weekday, null, null, startsAt, endsAt, room, weekKind);
    }

    /** A lesson placed in the period grid. */
    public static LessonInput inPeriods(Long subjectId, DayOfWeek weekday, int periodFrom, int periodTo,
                                        String room, WeekKind weekKind) {
        return new LessonInput(subjectId, weekday, periodFrom, periodTo, null, null, room, weekKind);
    }
}
