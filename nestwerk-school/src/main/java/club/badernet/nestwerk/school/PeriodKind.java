package club.badernet.nestwerk.school;

/** Row kind in the period grid of a school. */
public enum PeriodKind {
    LESSON("Stunde"),
    BREAK("Pause");

    private final String label;

    PeriodKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
