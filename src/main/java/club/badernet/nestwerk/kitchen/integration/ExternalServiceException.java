package club.badernet.nestwerk.kitchen.integration;

/** Ein Dienst außerhalb von Nestwerk (Open Food Facts, Bring!, Rezeptseite) ist nicht erreichbar oder lehnt ab. */
public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
