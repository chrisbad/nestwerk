package club.badernet.nestwerk.update;

/** Die Quelle für Versionen ist nicht erreichbar oder verweigert den Zugriff; die Meldung ist für Admins. */
public class UpdateSourceException extends RuntimeException {

    public UpdateSourceException(String message) {
        super(message);
    }

    public UpdateSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
