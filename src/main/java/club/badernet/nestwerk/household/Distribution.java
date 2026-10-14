package club.badernet.nestwerk.household;

/** Wer eine Aufgabe bekommt. */
public enum Distribution {
    FIXED("Fest zugewiesen"),
    ROTATION("Reihum"),
    BALANCED("Ausgleichend"),
    OPEN("Offen zum Nehmen");

    private final String label;

    Distribution(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
