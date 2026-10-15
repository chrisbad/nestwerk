package club.badernet.nestwerk.household;

/** Wie eine Hausarbeit fällig wird. */
public enum ChoreKind {
    DATES("Feste Termine"),
    RECURRING("Regelmäßig"),
    INTERVAL("Nach Erledigung"),
    ON_DEMAND("Bei Bedarf");

    private final String label;

    ChoreKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
