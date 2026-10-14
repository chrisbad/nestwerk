package club.badernet.nestwerk.todo;

/** Das ToDo wurde seit dem Öffnen des Formulars von jemand anderem geändert. */
public class TodoConflictException extends RuntimeException {

    public TodoConflictException(String changedBy) {
        super("Das ToDo wurde inzwischen von " + changedBy + " geändert. Lade die Seite neu, "
                + "um die aktuelle Fassung zu sehen; deine Eingaben stehen noch im Formular.");
    }
}
