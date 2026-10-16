package club.badernet.nestwerk.kitchen.integration;

import java.util.List;

/** Was Nestwerk von Bring! braucht. Alle Methoden werfen {@link BringException}, wenn Bring! nicht mitspielt. */
public interface BringApi {

    /** Meldet an und liefert die Listen des Kontos; zugleich der Test der Zugangsdaten. */
    List<BringList> lists(String email, String password);

    /** Namen der Artikel, die auf der Liste noch „zu kaufen“ sind. */
    List<String> open(String email, String password, String listUuid);

    /** Setzt einen Artikel mit Spezifikation („500 g“) auf die Liste. */
    void add(String email, String password, String listUuid, String name, String specification);
}
