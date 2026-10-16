package club.badernet.nestwerk.blocker;

/** Wie hart ein Blocker beim Einplanen greift. */
public enum BlockerStrength {
    SHOW("Nur anzeigen"),
    WARN("Warnen"),
    BLOCK("Sperren");

    private final String label;

    BlockerStrength(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
