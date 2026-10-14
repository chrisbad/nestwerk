package club.badernet.nestwerk.security.acl;

import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Zugriffsregeln: Matrix lesen, Regeln prüfen und (mit Eskalationsschutz) ändern. */
@Service
public class AclService {

    private static final RowMapper<AclRule> RULE = (rs, i) ->
            new AclRule(rs.getString("object_type"), rs.getString("action"), rs.getString("relation"));

    private final NamedParameterJdbcTemplate jdbc;
    private final RoleRepository roles;
    private final PermissionCatalog permissions;
    private final UserRepository users;

    public AclService(NamedParameterJdbcTemplate jdbc, RoleRepository roles, PermissionCatalog permissions,
                      UserRepository users) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.permissions = permissions;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AclObjectType> matrix() {
        List<AclRelation> relations = jdbc.query("SELECT key, label FROM acl_relations ORDER BY sort_order",
                (rs, i) -> new AclRelation(rs.getString("key"), rs.getString("label")));
        List<String[]> types = jdbc.query("SELECT key, label FROM acl_object_types ORDER BY sort_order",
                (rs, i) -> new String[]{rs.getString("key"), rs.getString("label")});
        Set<AclRule> cells = cells();
        List<AclObjectType> result = new ArrayList<>();
        for (String[] type : types) {
            List<AclAction> actions = jdbc.query(
                    "SELECT key, label FROM acl_actions WHERE object_type = :type ORDER BY sort_order",
                    Map.of("type", type[0]), (rs, i) -> new AclAction(rs.getString("key"), rs.getString("label")));
            Set<String> typeCells = new HashSet<>();
            Set<String> usedRelations = new HashSet<>();
            for (AclRule cell : cells) {
                if (cell.objectType().equals(type[0])) {
                    typeCells.add(cell.action() + ":" + cell.relation());
                    usedRelations.add(cell.relation());
                }
            }
            List<AclRelation> typeRelations = relations.stream().filter(r -> usedRelations.contains(r.key())).toList();
            result.add(new AclObjectType(type[0], type[1], actions, typeRelations, Set.copyOf(typeCells)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Set<AclRule> cells() {
        return new HashSet<>(jdbc.query("SELECT object_type, action, relation FROM acl_cells", RULE));
    }

    @Transactional(readOnly = true)
    public Set<AclRule> rulesOf(Long roleId) {
        return new HashSet<>(jdbc.query(
                "SELECT object_type, action, relation FROM acl_rules WHERE role_id = :id", Map.of("id", roleId), RULE));
    }

    /** Regelschlüssel je Rolle; Rollen ohne Regeln fehlen in der Map. */
    @Transactional(readOnly = true)
    public Map<Long, Set<String>> ruleKeysByRole() {
        Map<Long, Set<String>> result = new HashMap<>();
        jdbc.query("SELECT role_id, object_type, action, relation FROM acl_rules", rs -> {
            AclRule rule = RULE.mapRow(rs, 0);
            result.computeIfAbsent(rs.getLong("role_id"), k -> new LinkedHashSet<>()).add(rule.key());
        });
        return result;
    }

    @Transactional(readOnly = true)
    public Set<AclRule> rulesOf(User user) {
        if (user.isAdministrator()) {
            return cells();
        }
        // Rollen-IDs vom Objekt statt per Join auf user_roles: so zählen auch noch nicht geschriebene JPA-Änderungen.
        List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(jdbc.query("SELECT DISTINCT object_type, action, relation FROM acl_rules "
                + "WHERE role_id IN (:ids)", Map.of("ids", roleIds), RULE));
    }

    /** Erlaubt, wenn eine Regel der Rollen des Benutzers für eine der vorliegenden Beziehungen passt. */
    public boolean permits(User user, String objectType, String action, Set<String> relations) {
        return grantedRelations(user, objectType, action).stream().anyMatch(relations::contains);
    }

    /** Beziehungen, bei denen der Benutzer die Aktion ausführen darf (Grundlage für Listenabfragen). */
    @Transactional(readOnly = true)
    public Set<String> grantedRelations(User user, String objectType, String action) {
        Set<String> result = new LinkedHashSet<>();
        for (AclRule rule : rulesOf(user)) {
            if (rule.objectType().equals(objectType) && rule.action().equals(action)) {
                result.add(rule.relation());
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AclPreset> presets() {
        return jdbc.query("SELECT key, label FROM acl_presets ORDER BY sort_order",
                (rs, i) -> new AclPreset(rs.getString("key"), rs.getString("label")));
    }

    @Transactional
    public void replaceRules(User actor, Long roleId, Set<AclRule> rules) {
        Role role = roles.findById(roleId).orElseThrow(() -> new IllegalArgumentException("Rolle nicht gefunden"));
        if (role.isAllPermissions()) {
            throw new IllegalArgumentException("Die Administrator-Rolle hat immer alle Zugriffsrechte.");
        }
        Set<AclRule> valid = cells();
        for (AclRule rule : rules) {
            if (!valid.contains(rule)) {
                throw new IllegalArgumentException("Unbekannte Zugriffsregel: " + rule.key());
            }
        }
        if (!actor.isAdministrator()) {
            Set<AclRule> own = rulesOf(actor);
            if (!own.containsAll(rulesOf(roleId))) {
                throw new IllegalArgumentException("Diese Rolle hat Zugriffsregeln, die du selbst nicht hast.");
            }
            if (!own.containsAll(rules)) {
                throw new IllegalArgumentException("Du kannst nur Zugriffsregeln vergeben, die du selbst hast.");
            }
        }
        jdbc.update("DELETE FROM acl_rules WHERE role_id = :id", Map.of("id", roleId));
        for (AclRule rule : rules) {
            jdbc.update("INSERT INTO acl_rules (role_id, object_type, action, relation) VALUES (:id, :type, :action, :relation)",
                    Map.of("id", roleId, "type", rule.objectType(), "action", rule.action(), "relation", rule.relation()));
        }
    }

    @Transactional
    public void applyPreset(User actor, Long roleId, String presetKey) {
        boolean known = presets().stream().anyMatch(p -> p.key().equals(presetKey));
        if (!known) {
            throw new IllegalArgumentException("Unbekannte Vorlage: " + presetKey);
        }
        Set<AclRule> rules = new HashSet<>(jdbc.query(
                "SELECT object_type, action, relation FROM acl_preset_rules WHERE preset_key = :key",
                Map.of("key", presetKey), RULE));
        replaceRules(actor, roleId, rules);
    }

    /**
     * Ergänzt den Eskalationsschutz beim Zuweisen von Rollen: Nicht-Administratoren dürfen keine Rollen
     * vergeben und keine Benutzer ändern, deren Zugriffsregeln über die eigenen hinausgehen.
     */
    @Transactional(readOnly = true)
    public void requireMayAssign(String actorUsername, Collection<String> actorPermissions,
                                 Long targetUserId, Set<Long> roleIds) {
        if (permissions.isAdministrator(actorPermissions)) {
            return;
        }
        Set<AclRule> own = users.findByUsername(actorUsername).map(this::rulesOf).orElseGet(HashSet::new);
        if (targetUserId != null) {
            Set<AclRule> target = users.findById(targetUserId).map(this::rulesOf).orElseGet(HashSet::new);
            if (!own.containsAll(target)) {
                throw new IllegalArgumentException(
                        "Dieser Benutzer hat Zugriffsregeln, die du selbst nicht hast, und kann daher nicht geändert werden.");
            }
        }
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                if (!own.containsAll(rulesOf(roleId))) {
                    throw new IllegalArgumentException("Eine der Rollen enthält Zugriffsregeln, die du selbst nicht hast.");
                }
            }
        }
    }
}
