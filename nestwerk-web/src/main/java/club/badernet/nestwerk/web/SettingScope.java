package club.badernet.nestwerk.web;

/** Whom a setting applies to. */
public enum SettingScope {
    PERSONAL("Ich"),
    FAMILY("Familie"),
    SYSTEM("System");

    private final String label;

    SettingScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
