package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wertet das ACL für ToDos aus: ermittelt die Beziehungen eines Benutzers zu einem ToDo und übersetzt
 * die gewährten Beziehungen für Listen in eine SQL-Bedingung (Tabellenalias {@code t}).
 */
@Component
public class TodoAccess {

    public static final String TYPE = "TODO";

    /** SQL-Bedingung samt benannter Parameter. */
    public record VisibilitySql(String sql, Map<String, Object> params) {
    }

    private final AclService acl;
    private final FamilyMemberRepository members;
    private final UserRepository users;

    public TodoAccess(AclService acl, FamilyMemberRepository members, UserRepository users) {
        this.acl = acl;
        this.members = members;
        this.users = users;
    }

    /** Für die Navigation: darf der Benutzer das ToDo-Modul überhaupt öffnen? */
    @Transactional(readOnly = true)
    public boolean mayOpen(String username) {
        return users.findByUsername(username)
                .map(u -> !acl.grantedRelations(u, TYPE, "VIEW").isEmpty())
                .orElse(false);
    }

    public Set<String> relations(User user, Todo todo) {
        Set<String> relations = new HashSet<>();
        if (!todo.isPrivateTodo()) {
            relations.add("ANY");
        }
        if (user.getId().equals(todo.getResponsibleUserId())) {
            relations.add("RESPONSIBLE");
        }
        if (user.getId().equals(todo.getCreatedBy())) {
            relations.add("CREATOR");
        }
        if (todo.getWatcherUserIds().contains(user.getId())) {
            relations.add("WATCHING");
        }
        if (isAssigned(user, todo)) {
            relations.add("ASSIGNED");
        }
        return relations;
    }

    public boolean can(User user, String action, Todo todo) {
        return acl.permits(user, TYPE, action, relations(user, todo));
    }

    public boolean canCreate(User user) {
        return acl.permits(user, TYPE, "CREATE", Set.of("ANY"));
    }

    public boolean canCreatePrivate(User user) {
        return acl.permits(user, TYPE, "CREATE_PRIVATE", Set.of("ANY"));
    }

    /** Ist der Benutzer über sein Mitglied oder eine seiner Rollen zuständig? */
    public boolean isAssigned(User user, Todo todo) {
        Set<Long> roleIds = roleIds(user);
        Long memberId = memberId(user);
        for (AssigneeRef ref : todo.assigneeRefs()) {
            if (ref.type() == AssigneeRef.Type.MEMBER && ref.id().equals(memberId)) {
                return true;
            }
            if (ref.type() == AssigneeRef.Type.ROLE && roleIds.contains(ref.id())) {
                return true;
            }
        }
        return false;
    }

    /** Mitglied des Benutzers oder {@code null}. */
    public Long memberId(User user) {
        return members.findByUserId(user.getId()).map(m -> m.getId()).orElse(null);
    }

    /** Bedingung für ToDos, die der Benutzer sehen darf. */
    public VisibilitySql visibility(User user) {
        return condition(user, "VIEW");
    }

    /** Bedingung für ToDos, bei denen der Benutzer die Aktion ausführen darf. */
    public VisibilitySql condition(User user, String action) {
        if (user.isAdministrator()) {
            return new VisibilitySql("TRUE", Map.of());
        }
        Set<String> granted = acl.grantedRelations(user, TYPE, action);
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("accUser", user.getId());
        if (granted.contains("ANY")) {
            parts.add("NOT t.private_todo");
        }
        if (granted.contains("RESPONSIBLE")) {
            parts.add("t.responsible_user_id = :accUser");
        }
        if (granted.contains("CREATOR")) {
            parts.add("t.created_by = :accUser");
        }
        if (granted.contains("WATCHING")) {
            parts.add("EXISTS (SELECT 1 FROM todo_watchers w WHERE w.todo_id = t.id AND w.user_id = :accUser)");
        }
        if (granted.contains("ASSIGNED")) {
            parts.add(assignedSql(user, params));
        }
        if (parts.isEmpty()) {
            return new VisibilitySql("FALSE", Map.of());
        }
        return new VisibilitySql("(" + String.join(" OR ", parts) + ")", params);
    }

    /** SQL für „Benutzer ist zuständig“ (über Mitglied oder Rolle). */
    String assignedSql(User user, Map<String, Object> params) {
        List<String> or = new ArrayList<>();
        Long memberId = memberId(user);
        if (memberId != null) {
            params.put("accMember", memberId);
            or.add("a.member_id = :accMember");
        }
        Set<Long> roleIds = roleIds(user);
        if (!roleIds.isEmpty()) {
            params.put("accRoles", roleIds);
            or.add("a.role_id IN (:accRoles)");
        }
        if (or.isEmpty()) {
            return "FALSE";
        }
        return "EXISTS (SELECT 1 FROM todo_assignees a WHERE a.todo_id = t.id AND (" + String.join(" OR ", or) + "))";
    }

    private static Set<Long> roleIds(User user) {
        Set<Long> ids = new HashSet<>();
        for (Role r : user.getRoles()) {
            ids.add(r.getId());
        }
        return ids;
    }
}
