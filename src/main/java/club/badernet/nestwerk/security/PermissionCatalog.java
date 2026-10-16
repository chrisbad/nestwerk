package club.badernet.nestwerk.security;

import club.badernet.nestwerk.calendar.CalendarPermission;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Rechtekatalog aus der Tabelle {@code permissions} und Berechnung effektiver Rechte. */
@Component
public class PermissionCatalog {

    private final JdbcTemplate jdbc;
    private volatile Set<String> keys;

    public PermissionCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alle Schlüssel, die der Code prüft. Jedes Modul-Enum wird hier ergänzt. */
    public static Set<String> codeKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Stream.of(AdminPermission.values(), CalendarPermission.values(), KitchenPermission.values())
                .flatMap(Arrays::stream)
                .forEach(p -> keys.add(p.name()));
        return keys;
    }

    public List<PermissionDefinition> all() {
        return jdbc.query("SELECT key, module, label, description, sort_order, system FROM permissions "
                        + "ORDER BY sort_order, key",
                (rs, i) -> new PermissionDefinition(rs.getString("key"), rs.getString("module"),
                        rs.getString("label"), rs.getString("description"), rs.getInt("sort_order"),
                        rs.getBoolean("system")));
    }

    /** Schlüssel ändern sich nur durch Migrationen, daher einmalig geladen. */
    public Set<String> keys() {
        Set<String> cached = keys;
        if (cached == null) {
            Set<String> loaded = new LinkedHashSet<>();
            all().forEach(p -> loaded.add(p.key()));
            cached = Set.copyOf(loaded);
            keys = cached;
        }
        return cached;
    }

    public boolean exists(String key) {
        return keys().contains(key);
    }

    public Map<String, List<PermissionDefinition>> byModule() {
        Map<String, List<PermissionDefinition>> grouped = new LinkedHashMap<>();
        for (PermissionDefinition p : all()) {
            grouped.computeIfAbsent(p.module(), k -> new ArrayList<>()).add(p);
        }
        return grouped;
    }

    public Set<String> effective(Role role) {
        return role.isAllPermissions() ? keys() : role.getPermissions();
    }

    public Set<String> effective(User user) {
        if (user.isAdministrator()) {
            return keys();
        }
        Set<String> names = new LinkedHashSet<>();
        user.getRoles().forEach(r -> names.addAll(r.getPermissions()));
        return names;
    }

    public boolean isAdministrator(Collection<String> permissions) {
        return permissions.containsAll(keys());
    }

    @Transactional
    public void updateText(String key, String label, String description) {
        String cleanLabel = label == null ? "" : label.trim();
        if (cleanLabel.isEmpty() || cleanLabel.length() > 100) {
            throw new IllegalArgumentException("Die Bezeichnung muss 1–100 Zeichen lang sein.");
        }
        String cleanDescription = description == null || description.isBlank() ? null : description.trim();
        if (cleanDescription != null && cleanDescription.length() > 255) {
            throw new IllegalArgumentException("Die Beschreibung darf höchstens 255 Zeichen lang sein.");
        }
        int updated = jdbc.update("UPDATE permissions SET label = ?, description = ? WHERE key = ?",
                cleanLabel, cleanDescription, key);
        if (updated == 0) {
            throw new IllegalArgumentException("Recht nicht gefunden");
        }
    }
}
