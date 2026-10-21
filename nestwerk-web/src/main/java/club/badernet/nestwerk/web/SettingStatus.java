package club.badernet.nestwerk.web;

/** Whether a setting needs the user's attention. */
public enum SettingStatus {
    OK("in Ordnung"),
    ATTENTION("Aufmerksamkeit nötig"),
    NOT_SET("nicht eingerichtet");

    private final String label;

    SettingStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
