package club.badernet.nestwerk.kitchen.integration;

/** Bring! ist nicht erreichbar, lehnt die Anmeldung ab oder antwortet unerwartet. */
public class BringException extends ExternalServiceException {

    public BringException(String message, Throwable cause) {
        super(message, cause);
    }

    public BringException(String message) {
        super(message, null);
    }
}
