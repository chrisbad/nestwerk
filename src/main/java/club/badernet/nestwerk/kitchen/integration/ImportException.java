package club.badernet.nestwerk.kitchen.integration;

/** Eine Seite ließ sich nicht als Rezept lesen; die Meldung ist für Nutzer gedacht. */
public class ImportException extends IllegalArgumentException {

    public ImportException(String message) {
        super(message);
    }
}
