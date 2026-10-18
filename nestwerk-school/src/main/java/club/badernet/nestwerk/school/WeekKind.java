package club.badernet.nestwerk.school;

/** In welchen Wochen eine Stunde stattfindet; A und B wechseln sich ab. */
public enum WeekKind {
    EVERY("Jede Woche"),
    A("A-Woche"),
    B("B-Woche");

    private final String label;

    WeekKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
