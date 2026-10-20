package club.badernet.nestwerk.school;

/** Kind of a school appointment. */
public enum ExamKind {
    EXAM("Klassenarbeit"),
    TEST("Test"),
    PARENTS_EVENING("Elternabend"),
    PARENT_TALK("Elterngespräch"),
    TRIP("Ausflug"),
    OTHER("Termin");

    private final String label;

    ExamKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Exam or test (with subject and study reminder); otherwise a school appointment. */
    public boolean isExam() {
        return this == EXAM || this == TEST;
    }

    /** Appointments for the parents instead of the child (default participants). */
    public boolean forParents() {
        return this == PARENTS_EVENING || this == PARENT_TALK;
    }
}
