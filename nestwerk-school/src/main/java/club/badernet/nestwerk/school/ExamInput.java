package club.badernet.nestwerk.school;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Input for exams and school appointments. Without {@code startsAt} the appointment is all-day; without
 * {@code memberIds} the default participants of the kind apply (child or parents).
 */
public record ExamInput(Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays,
                        LocalTime startsAt, LocalTime endsAt, String location, Set<Long> memberIds) {

    /** All-day with default participants. */
    public static ExamInput allDay(Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays) {
        return new ExamInput(subjectId, kind, title, day, studyDays, null, null, null, null);
    }
}
