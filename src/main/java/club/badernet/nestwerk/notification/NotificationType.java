package club.badernet.nestwerk.notification;

/** Anlass einer Benachrichtigung; Erinnerungen und Ereignisse. */
public enum NotificationType {
    TODO_AVAILABLE("„Ab“ erreicht", true),
    TODO_DUE_SOON("Bald fällig", true),
    TODO_OVERDUE("Überfällig", true),
    TODO_FOLLOW_UP("Wiedervorlage", true),
    TODO_ASSIGNED("Zugewiesen", false),
    TODO_COMMENT("Neuer Kommentar", false),
    TODO_MENTION("Erwähnung", false),
    TODO_STATUS("Statuswechsel", false),
    TODO_UNBLOCKED("Nicht mehr blockiert", false);

    private final String label;
    private final boolean reminder;

    NotificationType(String label, boolean reminder) {
        this.label = label;
        this.reminder = reminder;
    }

    public String label() {
        return label;
    }

    public boolean isReminder() {
        return reminder;
    }
}
