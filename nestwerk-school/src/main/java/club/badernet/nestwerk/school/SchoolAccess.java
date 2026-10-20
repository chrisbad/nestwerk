package club.badernet.nestwerk.school;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/** Checks the school permissions of a user via their roles. */
@Component
public class SchoolAccess {

    private final PermissionCatalog permissions;
    private final FamilyMemberService members;
    private final UserRepository users;

    public SchoolAccess(PermissionCatalog permissions, FamilyMemberService members, UserRepository users) {
        this.permissions = permissions;
        this.members = members;
        this.users = users;
    }

    /** Parents in terms of the school module: members whose login may edit school data. */
    @Transactional(readOnly = true)
    public Set<Long> parentMemberIds() {
        return members.findAll().stream()
                .filter(m -> m.getUserId() != null)
                .filter(m -> users.findById(m.getUserId()).filter(User::isEnabled)
                        .map(u -> has(u, SchoolPermission.SCHOOL_EDIT)).orElse(false))
                .map(FamilyMember::getId)
                .collect(Collectors.toSet());
    }

    public boolean has(User user, SchoolPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    public void require(User user, SchoolPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung für diese Schulfunktion");
        }
    }

    /** Viewing grades: all with {@code SCHOOL_GRADES}, only the own member's with {@code SCHOOL_GRADES_OWN}. */
    public boolean mayViewGrades(User user, Long memberId) {
        if (has(user, SchoolPermission.SCHOOL_GRADES)) {
            return true;
        }
        return has(user, SchoolPermission.SCHOOL_GRADES_OWN)
                && members.findByUserId(user.getId()).map(FamilyMember::getId).filter(memberId::equals).isPresent();
    }
}
