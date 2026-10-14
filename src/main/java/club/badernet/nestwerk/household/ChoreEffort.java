package club.badernet.nestwerk.household;

/** Aufwandsgrad; wird in der Auswertung getrennt von den Minuten gezählt. */
public enum ChoreEffort {
    EASY("leicht"),
    MEDIUM("mittel"),
    HARD("schwer");

    private final String label;

    ChoreEffort(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
