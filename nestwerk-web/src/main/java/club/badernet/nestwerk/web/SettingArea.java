package club.badernet.nestwerk.web;

/** The part of the app a setting belongs to. */
public enum SettingArea {
    CALENDAR("Kalender"),
    HOUSEHOLD("Haushalt"),
    KITCHEN("Küche"),
    SCHOOL("Schule"),
    NOTIFICATIONS("Benachrichtigungen"),
    ACCOUNT("Konto"),
    SYSTEM("Familie & System");

    private final String label;

    SettingArea(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
