package club.badernet.nestwerk.blocker;

/** Was andere (nicht Betroffene) von einem Blocker sehen. */
public enum BlockerVisibility {
    TITLE("Mit Titel"),
    BUSY("Nur „belegt“"),
    HIDDEN("Unsichtbar");

    private final String label;

    BlockerVisibility(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
