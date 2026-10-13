package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.security.Permission;

public enum CalendarPermission implements Permission {
    CALENDAR_VIEW("Kalender ansehen"),
    CALENDAR_EVENT_CREATE("Termine anlegen"),
    CALENDAR_EVENT_EDIT_OWN("Eigene Termine bearbeiten"),
    CALENDAR_EVENT_EDIT_ALL("Alle Termine bearbeiten"),
    CALENDAR_EVENT_DELETE_OWN("Eigene Termine löschen"),
    CALENDAR_EVENT_DELETE_ALL("Alle Termine löschen"),
    CALENDAR_PRIVATE_CREATE("Private Termine anlegen");

    private final String label;

    CalendarPermission(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String module() {
        return "Kalender";
    }
}
