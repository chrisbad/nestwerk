package club.badernet.nestwerk.school;

/** Art einer Note. */
public enum GradeKind {
    WRITTEN("Schriftlich"),
    ORAL("Mündlich"),
    OTHER("Sonstige");

    private final String label;

    GradeKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
