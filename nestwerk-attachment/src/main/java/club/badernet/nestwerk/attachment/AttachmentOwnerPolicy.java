package club.badernet.nestwerk.attachment;

import club.badernet.nestwerk.user.User;

/**
 * Rechteprüfung für Anhänge eines Besitzertyps, den ein anderes Modul liefert (z. B. Elternbriefe der Schule). So
 * muss das Anhangsmodul diese Module nicht kennen.
 */
public interface AttachmentOwnerPolicy {

    AttachmentOwner.Type type();

    /** Wirft {@code AccessDeniedException}, wenn der Benutzer nichts anhängen darf. */
    void requireMayAttach(Long ownerId, User actor);

    /** Wirft eine Exception, wenn der Besitzer für den Betrachter nicht sichtbar ist. */
    void requireVisible(Long ownerId, User viewer);

    boolean mayEdit(Long ownerId, User actor);
}
