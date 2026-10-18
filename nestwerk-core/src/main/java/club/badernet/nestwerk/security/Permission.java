package club.badernet.nestwerk.security;

/**
 * Schlüssel eines Rechts, wie ihn der Code prüft. Bezeichnung, Modul und Reihenfolge stehen in der
 * Tabelle {@code permissions}; jedes Modul liefert ein Enum, das dieses Interface implementiert.
 */
public interface Permission {

    /** Eindeutiger Schlüssel; wird in der DB und als Spring-Security-Authority verwendet. */
    String name();
}
