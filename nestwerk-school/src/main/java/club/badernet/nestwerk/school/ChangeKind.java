package club.badernet.nestwerk.school;

/** Change of a lesson on a single day. */
public enum ChangeKind {
    CANCELLED("Entfällt"),
    SUBSTITUTE("Vertretung"),
    /** Different room; the new room is stored in the note. */
    ROOM("Raumwechsel");

    private final String label;

    ChangeKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
