package club.badernet.nestwerk.school;

/**
 * Selects lessons of a day by subject name and/or period range; without both, all lessons of the day are selected.
 * Lessons with free times only match when no period range is given.
 */
public record LessonSelection(String subject, Integer periodFrom, Integer periodTo) {

    public static LessonSelection wholeDay() {
        return new LessonSelection(null, null, null);
    }

    boolean matches(Timetable.Entry entry) {
        if (subject != null && !subject.isBlank() && !entry.subject().getName().equalsIgnoreCase(subject.trim())) {
            return false;
        }
        if (periodFrom == null) {
            return true;
        }
        Lesson lesson = entry.lesson();
        int to = periodTo == null ? periodFrom : periodTo;
        return lesson.inPeriods() && lesson.getPeriodFrom() <= to && lesson.getPeriodTo() >= periodFrom;
    }
}
