package club.badernet.nestwerk.security;

import java.util.Collection;

/** Liefert die Rechte, die ein Modul im Code prüft. Der Katalog fasst alle Quellen zusammen. */
public interface PermissionSource {

    Collection<? extends Permission> permissions();
}
