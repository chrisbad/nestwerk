package club.badernet.nestwerk.security;

/** Ein einzelnes Recht. Jedes Modul liefert ein Enum, das dieses Interface implementiert. */
public interface Permission {

    /** Eindeutiger Name; wird in der DB und als Spring-Security-Authority verwendet. */
    String name();

    /** Anzeigename für die Rollenverwaltung. */
    String label();

    /** Gruppenname für die Rollenverwaltung. */
    String module();
}
