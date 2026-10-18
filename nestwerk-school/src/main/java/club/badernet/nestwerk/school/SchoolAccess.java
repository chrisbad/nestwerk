package club.badernet.nestwerk.school;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Prüft die Schulrechte eines Benutzers über seine Rollen. */
@Component
public class SchoolAccess {

    private final PermissionCatalog permissions;
    private final FamilyMemberService members;

    public SchoolAccess(PermissionCatalog permissions, FamilyMemberService members) {
        this.permissions = permissions;
        this.members = members;
    }

    public boolean has(User user, SchoolPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    public void require(User user, SchoolPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung für diese Schulfunktion");
        }
    }

    /** Noten sehen: mit {@code SCHOOL_GRADES} alle, mit {@code SCHOOL_GRADES_OWN} nur die des eigenen Mitglieds. */
    public boolean mayViewGrades(User user, Long memberId) {
        if (has(user, SchoolPermission.SCHOOL_GRADES)) {
            return true;
        }
        return has(user, SchoolPermission.SCHOOL_GRADES_OWN)
                && members.findByUserId(user.getId()).map(FamilyMember::getId).filter(memberId::equals).isPresent();
    }
}
