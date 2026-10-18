package club.badernet.nestwerk.school;

/** Art eines Schultermins. */
public enum ExamKind {
    EXAM("Klassenarbeit"),
    TEST("Test"),
    PARENTS_EVENING("Elternabend"),
    TRIP("Ausflug"),
    OTHER("Termin");

    private final String label;

    ExamKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
