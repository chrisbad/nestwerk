package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Wertet das ACL für Hausarbeiten aus. Beziehungen: {@code ANY} immer, {@code CREATOR} für den Ersteller der
 * Hausarbeit, {@code ASSIGNED} wenn das eigene Mitglied Kandidat bzw. Zuständiger ist.
 */
@Component
public class HouseholdAccess {

    public static final String TYPE = "CHORE";

    private final AclService acl;
    private final FamilyMemberRepository members;
    private final UserRepository users;

    public HouseholdAccess(AclService acl, FamilyMemberRepository members, UserRepository users) {
        this.acl = acl;
        this.members = members;
        this.users = users;
    }

    /** Für die Navigation: darf der Benutzer den Putzplan öffnen? */
    @Transactional(readOnly = true)
    public boolean mayOpen(String username) {
        return users.findByUsername(username)
                .map(u -> !acl.grantedRelations(u, TYPE, "VIEW").isEmpty())
                .orElse(false);
    }

    /** Das Familienmitglied des Benutzers, falls verknüpft. */
    public Optional<Long> memberId(User user) {
        return members.findByUserId(user.getId()).map(FamilyMember::getId);
    }

    public Set<String> relations(User user, Chore chore) {
        Set<String> relations = new HashSet<>();
        relations.add("ANY");
        if (user.getId().equals(chore.getCreatedBy())) {
            relations.add("CREATOR");
        }
        memberId(user).filter(chore.getMemberIds()::contains).ifPresent(m -> relations.add("ASSIGNED"));
        return relations;
    }

    public Set<String> relations(User user, ChoreTask task) {
        Set<String> relations = new HashSet<>();
        relations.add("ANY");
        if (user.getId().equals(task.getChore().getCreatedBy())) {
            relations.add("CREATOR");
        }
        Optional<Long> own = memberId(user);
        if (own.isPresent() && isResponsible(own.get(), task)) {
            relations.add("ASSIGNED");
        }
        return relations;
    }

    /** Zuständig: als Zuständiger eingetragen oder – solange niemand zuständig ist – Kandidat bzw. bei offenen ohne Kandidaten jeder. */
    static boolean isResponsible(Long memberId, ChoreTask task) {
        if (task.getAssigneeMemberId() != null) {
            return task.getAssigneeMemberId().equals(memberId);
        }
        return task.getChore().getMemberIds().isEmpty() || task.getChore().getMemberIds().contains(memberId);
    }

    public boolean can(User user, String action, Chore chore) {
        return acl.permits(user, TYPE, action, relations(user, chore));
    }

    public boolean can(User user, String action, ChoreTask task) {
        return acl.permits(user, TYPE, action, relations(user, task));
    }

    /** Recht unabhängig von einer Beziehung (Anlegen, für andere abhaken, Auswertung aller). */
    public boolean canAny(User user, String action) {
        return acl.permits(user, TYPE, action, Set.of("ANY"));
    }
}
