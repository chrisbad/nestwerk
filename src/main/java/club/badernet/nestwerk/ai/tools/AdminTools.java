package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.security.PermissionDefinition;
import club.badernet.nestwerk.security.acl.AclObjectType;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Verwaltung: Benutzer (Rollen, Aktivierung), Familienmitglieder, Rollen mit Rechten und Zugriffsregeln. Jedes Tool
 * verlangt dasselbe Verwaltungsrecht wie die Weboberfläche und unterliegt denselben Regeln gegen Rechteausweitung.
 * Passwörter setzen und Benutzer anlegen gibt es hier bewusst nicht – Zugangsdaten gehören nicht in einen
 * KI-Verlauf.
 */
@Component
public class AdminTools {

    public record UserItem(Long userId, String username, boolean enabled, boolean administrator, String email,
                           List<String> roles, String member) {
    }

    public record RoleItem(Long roleId, String name, String description, boolean systemRole,
                           boolean allPermissions, List<String> permissions, List<String> accessRules) {
    }

    public record PermissionItem(String key, String module, String label, String description) {
    }

    public record AccessCatalog(List<ObjectTypeItem> objectTypes, List<String> presets) {
    }

    public record ObjectTypeItem(String type, String label, List<String> actions, List<String> relations,
                                 List<String> allowedCells) {
    }

    public record MemberItem(Long memberId, String name, String color, Long userId) {
    }

    private final Tools tools;
    private final AdminGuard guard;
    private final UserService userService;
    private final RoleService roleService;
    private final FamilyMemberService memberService;
    private final PermissionCatalog catalog;
    private final AclService acl;

    public AdminTools(Tools tools, AdminGuard guard, UserService userService, RoleService roleService,
                      FamilyMemberService memberService, PermissionCatalog catalog, AclService acl) {
        this.tools = tools;
        this.guard = guard;
        this.userService = userService;
        this.roleService = roleService;
        this.memberService = memberService;
        this.catalog = catalog;
        this.acl = acl;
    }

    // --- Benutzer ------------------------------------------------------------------------

    @McpTool(name = "list_users", description = "Verwaltung: Listet alle Benutzerkonten mit Rollen und zugehörigem "
            + "Familienmitglied. Braucht USER_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<UserItem> listUsers(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.USER_MANAGE);
            Map<Long, String> memberOfUser = memberService.findAll().stream().filter(m -> m.getUserId() != null)
                    .collect(Collectors.toMap(FamilyMember::getUserId, FamilyMember::getName, (a, b) -> a));
            return userService.findAll().stream().map(u -> userItem(u, memberOfUser)).toList();
        });
    }

    @McpTool(name = "set_user_roles", description = "Verwaltung: Setzt die Rollen eines Benutzers (roleIds aus "
            + "list_roles, ersetzt die bisherigen). Man darf nur Rollen vergeben, deren Rechte man selbst hat. "
            + "Braucht USER_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public UserItem setUserRoles(McpTransportContext context,
                                 @McpToolParam(description = "Benutzer-ID aus list_users") Long userId,
                                 @McpToolParam(description = "IDs der Rollen") List<Long> roleIds) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.USER_MANAGE);
            Long id = Tools.required(userId, "userId");
            Set<Long> roles = new HashSet<>(Tools.required(roleIds, "roleIds"));
            Set<String> permissions = catalog.effective(actor);
            userService.requireMayManage(permissions, id, roles);
            acl.requireMayAssign(actor.getUsername(), permissions, id, roles);
            userService.changeRoles(id, roles);
            return find(id);
        });
    }

    @McpTool(name = "set_user_enabled", description = "Verwaltung: Aktiviert oder deaktiviert ein Benutzerkonto "
            + "(der letzte Administrator lässt sich nicht deaktivieren). Braucht USER_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public UserItem setUserEnabled(McpTransportContext context,
                                   @McpToolParam(description = "Benutzer-ID aus list_users") Long userId,
                                   @McpToolParam(description = "true = aktiv, false = gesperrt") Boolean enabled) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.USER_MANAGE);
            Long id = Tools.required(userId, "userId");
            Set<String> permissions = catalog.effective(actor);
            userService.requireMayManage(permissions, id, null);
            acl.requireMayAssign(actor.getUsername(), permissions, id, null);
            userService.setEnabled(id, Tools.required(enabled, "enabled"));
            return find(id);
        });
    }

    // --- Familienmitglieder ----------------------------------------------------------------

    @McpTool(name = "create_member", description = "Verwaltung: Legt ein Familienmitglied an (Name, Farbe #RRGGBB, "
            + "optional das verknüpfte Benutzerkonto). Braucht MEMBER_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public MemberItem createMember(McpTransportContext context,
                                   @McpToolParam(description = "Name") String name,
                                   @McpToolParam(description = "Farbe als #RRGGBB") String color,
                                   @McpToolParam(description = "Benutzer-ID des Kontos", required = false)
                                   Long userId) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.MEMBER_MANAGE);
            return member(memberService.create(name, color, userId));
        });
    }

    @McpTool(name = "update_member", description = "Verwaltung: Ändert ein Familienmitglied. Nicht angegebene Felder "
            + "bleiben; unlinkUser=true löst die Verknüpfung zum Konto. Braucht MEMBER_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public MemberItem updateMember(McpTransportContext context,
                                   @McpToolParam(description = "memberId aus list_members") Long memberId,
                                   @McpToolParam(description = "Name", required = false) String name,
                                   @McpToolParam(description = "Farbe als #RRGGBB", required = false) String color,
                                   @McpToolParam(description = "Benutzer-ID des Kontos", required = false)
                                   Long userId,
                                   @McpToolParam(description = "Verknüpfung zum Konto lösen", required = false)
                                   Boolean unlinkUser) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.MEMBER_MANAGE);
            Long id = Tools.required(memberId, "memberId");
            FamilyMember current = memberService.findAll().stream().filter(m -> m.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Mitglied nicht gefunden"));
            Long linked = Boolean.TRUE.equals(unlinkUser) ? null : userId != null ? userId : current.getUserId();
            memberService.update(id, name != null ? name : current.getName(),
                    color != null ? color : current.getColor(), linked);
            return member(memberService.findAll().stream().filter(m -> m.getId().equals(id)).findFirst()
                    .orElseThrow());
        });
    }

    // --- Rollen und Rechte -------------------------------------------------------------------

    @McpTool(name = "list_roles", description = "Verwaltung: Listet Rollen mit ihren Rechten (Schlüssel aus "
            + "list_permissions) und Zugriffsregeln (TYP:AKTION:BEZIEHUNG). Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<RoleItem> listRoles(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.ACCESS_ROLE_MANAGE);
            Map<Long, Set<String>> rules = acl.ruleKeysByRole();
            return roleService.findAll().stream().map(r -> role(r, rules)).toList();
        });
    }

    @McpTool(name = "list_permissions", description = "Verwaltung: Listet alle Rechte mit Modul, Bezeichnung und "
            + "Beschreibung. Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<PermissionItem> listPermissions(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.ACCESS_ROLE_MANAGE);
            return catalog.all().stream().map(AdminTools::permission).toList();
        });
    }

    @McpTool(name = "update_permission_text", description = "Verwaltung: Ändert Bezeichnung und Beschreibung eines "
            + "Rechts (nur die Texte, nicht die Wirkung). Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public PermissionItem updatePermissionText(McpTransportContext context,
                                               @McpToolParam(description = "Schlüssel des Rechts") String key,
                                               @McpToolParam(description = "Bezeichnung", required = false)
                                               String label,
                                               @McpToolParam(description = "Beschreibung", required = false)
                                               String description) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.ACCESS_ROLE_MANAGE);
            PermissionDefinition current = catalog.all().stream().filter(p -> p.key().equals(key)).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Unbekanntes Recht: " + key));
            catalog.updateText(key, label != null ? label : current.label(),
                    description != null ? description : current.description());
            return permission(catalog.all().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow());
        });
    }

    @McpTool(name = "create_role", description = "Verwaltung: Legt eine Rolle mit Rechten an (permissions: Schlüssel "
            + "aus list_permissions). Man darf nur Rechte vergeben, die man selbst hat. Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public RoleItem createRole(McpTransportContext context,
                               @McpToolParam(description = "Name") String name,
                               @McpToolParam(description = "Beschreibung", required = false) String description,
                               @McpToolParam(description = "Rechte-Schlüssel", required = false)
                               List<String> permissions) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.ACCESS_ROLE_MANAGE);
            Set<String> wanted = permissions == null ? Set.of() : new HashSet<>(permissions);
            roleService.requireMayManage(catalog.effective(actor), null, wanted);
            Role role = roleService.create(name, description, wanted);
            return role(role, acl.ruleKeysByRole());
        });
    }

    @McpTool(name = "update_role", description = "Verwaltung: Ändert eine Rolle. Nicht angegebene Felder bleiben; "
            + "permissions ersetzt die Rechte. Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public RoleItem updateRole(McpTransportContext context,
                               @McpToolParam(description = "roleId aus list_roles") Long roleId,
                               @McpToolParam(description = "Name", required = false) String name,
                               @McpToolParam(description = "Beschreibung", required = false) String description,
                               @McpToolParam(description = "Rechte-Schlüssel (ersetzt)", required = false)
                               List<String> permissions) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.ACCESS_ROLE_MANAGE);
            Long id = Tools.required(roleId, "roleId");
            Role current = roleService.findAll().stream().filter(r -> r.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Rolle nicht gefunden"));
            Set<String> wanted = permissions != null ? new HashSet<>(permissions)
                    : new HashSet<>(current.getPermissions());
            roleService.requireMayManage(catalog.effective(actor), id, wanted);
            roleService.update(id, name != null ? name : current.getName(),
                    description != null ? description : current.getDescription(), wanted);
            return role(roleService.findAll().stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow(),
                    acl.ruleKeysByRole());
        });
    }

    @McpTool(name = "access_catalog", description = "Verwaltung: Zeigt, welche Zugriffsregeln es gibt – je "
            + "Objekttyp die Aktionen, Beziehungen und erlaubten Kombinationen (AKTION:BEZIEHUNG) – und die "
            + "Vorlagen für set_role_access_rules und apply_access_preset. Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public AccessCatalog accessCatalog(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.ACCESS_ROLE_MANAGE);
            return new AccessCatalog(acl.matrix().stream().map(AdminTools::objectType).toList(),
                    acl.presets().stream().map(p -> p.key() + " (" + p.label() + ")").toList());
        });
    }

    @McpTool(name = "set_role_access_rules", description = "Verwaltung: Ersetzt die Zugriffsregeln einer Rolle "
            + "(Format TYP:AKTION:BEZIEHUNG, siehe access_catalog). Man darf nur Regeln vergeben, die man selbst "
            + "hat. Braucht ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = true))
    public RoleItem setRoleAccessRules(McpTransportContext context,
                                       @McpToolParam(description = "roleId aus list_roles") Long roleId,
                                       @McpToolParam(description = "Regeln, z. B. TODO:VIEW:ANY") List<String> rules) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.ACCESS_ROLE_MANAGE);
            Long id = Tools.required(roleId, "roleId");
            Set<AclRule> parsed = Tools.required(rules, "rules").stream().map(AclRule::parse)
                    .collect(Collectors.toSet());
            acl.replaceRules(actor, id, parsed);
            return role(roleService.findAll().stream().filter(r -> r.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Rolle nicht gefunden")), acl.ruleKeysByRole());
        });
    }

    @McpTool(name = "apply_access_preset", description = "Verwaltung: Übernimmt eine Vorlage (key aus "
            + "access_catalog, z. B. ELTERNTEIL oder KIND) als Zugriffsregeln einer Rolle. Braucht "
            + "ACCESS_ROLE_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = true))
    public RoleItem applyAccessPreset(McpTransportContext context,
                                      @McpToolParam(description = "roleId aus list_roles") Long roleId,
                                      @McpToolParam(description = "Schlüssel der Vorlage") String preset) {
        return tools.write(context, actor -> {
            guard.require(actor, AdminPermission.ACCESS_ROLE_MANAGE);
            Long id = Tools.required(roleId, "roleId");
            acl.applyPreset(actor, id, Tools.required(preset, "preset"));
            return role(roleService.findAll().stream().filter(r -> r.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Rolle nicht gefunden")), acl.ruleKeysByRole());
        });
    }

    // --- Hilfen ------------------------------------------------------------------------------

    private UserItem find(Long id) {
        Map<Long, String> memberOfUser = memberService.findAll().stream().filter(m -> m.getUserId() != null)
                .collect(Collectors.toMap(FamilyMember::getUserId, FamilyMember::getName, (a, b) -> a));
        return userService.findAll().stream().filter(u -> u.getId().equals(id)).findFirst()
                .map(u -> userItem(u, memberOfUser)).orElseThrow(() -> new Tools.ToolException("Benutzer nicht gefunden"));
    }

    private static UserItem userItem(User u, Map<Long, String> memberOfUser) {
        return new UserItem(u.getId(), u.getUsername(), u.isEnabled(), u.isAdministrator(), u.getEmail(),
                u.getRoles().stream().map(r -> r.getName() + " (" + r.getId() + ")").sorted().toList(),
                memberOfUser.get(u.getId()));
    }

    private static MemberItem member(FamilyMember m) {
        return new MemberItem(m.getId(), m.getName(), m.getColor(), m.getUserId());
    }

    private static RoleItem role(Role r, Map<Long, Set<String>> rules) {
        return new RoleItem(r.getId(), r.getName(), r.getDescription(), r.isSystemRole(), r.isAllPermissions(),
                new TreeSet<>(r.getPermissions()).stream().toList(),
                new TreeSet<>(rules.getOrDefault(r.getId(), Set.of())).stream().toList());
    }

    private static PermissionItem permission(PermissionDefinition p) {
        return new PermissionItem(p.key(), p.module(), p.label(), p.description());
    }

    private static ObjectTypeItem objectType(AclObjectType t) {
        return new ObjectTypeItem(t.key(), t.label(),
                t.actions().stream().map(a -> a.key() + " (" + a.label() + ")").toList(),
                t.relations().stream().map(r -> r.key() + " (" + r.label() + ")").toList(),
                new TreeSet<>(t.cells()).stream().toList());
    }
}
