package club.badernet.nestwerk.school;

/** Abweichung einer Stunde an einem einzelnen Tag. */
public enum ChangeKind {
    CANCELLED("Entfällt"),
    SUBSTITUTE("Vertretung");

    private final String label;

    ChangeKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
