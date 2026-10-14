package club.badernet.nestwerk.notification;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Welche Kanäle für einen Anlass gelten. Aufgelöst wird vom Feinsten zum Gröbsten:
 * ToDo-Ausnahme (für alle Anlässe) → Benutzer → global → Vorgabe (Push an, E-Mail aus).
 */
@Component
public class NotificationPreferences {

    public static final String ALL = "ALL";

    public enum Scope { GLOBAL, USER, TODO }

    public enum Choice {
        DEFAULT("Standard"), ON("An"), OFF("Aus");

        private final String label;

        Choice(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationPreferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public boolean enabled(Channel channel, NotificationType type, Long userId, Long todoId) {
        if (todoId != null) {
            Boolean todo = lookup(Scope.TODO, todoId, ALL, channel);
            if (todo != null) {
                return todo;
            }
        }
        if (userId != null) {
            Boolean user = lookup(Scope.USER, userId, type.name(), channel);
            if (user != null) {
                return user;
            }
        }
        Boolean global = lookup(Scope.GLOBAL, 0L, type.name(), channel);
        if (global != null) {
            return global;
        }
        return channel == Channel.PUSH;
    }

    @Transactional(readOnly = true)
    public Choice choice(Scope scope, Long scopeId, String type, Channel channel) {
        Boolean value = lookup(scope, scopeId, type, channel);
        return value == null ? Choice.DEFAULT : value ? Choice.ON : Choice.OFF;
    }

    /** Alle gesetzten Werte eines Bereichs als {@code TYP:KANAL → Wahl}. */
    @Transactional(readOnly = true)
    public Map<String, Choice> choices(Scope scope, Long scopeId) {
        Map<String, Choice> result = new HashMap<>();
        jdbc.query("SELECT type, channel, enabled FROM notification_preferences WHERE scope = :scope AND scope_id = :id",
                Map.of("scope", scope.name(), "id", scopeId),
                rs -> {
                    result.put(rs.getString("type") + ":" + rs.getString("channel"),
                            rs.getBoolean("enabled") ? Choice.ON : Choice.OFF);
                });
        return result;
    }

    /** Effektiver globaler Wert (gesetzt oder Vorgabe). */
    public boolean globalEnabled(NotificationType type, Channel channel) {
        Boolean value = lookup(Scope.GLOBAL, 0L, type.name(), channel);
        return value != null ? value : channel == Channel.PUSH;
    }

    @Transactional
    public void set(Scope scope, Long scopeId, String type, Channel channel, Choice choice) {
        boolean validType = (scope == Scope.TODO && ALL.equals(type))
                || (scope != Scope.TODO && Arrays.stream(NotificationType.values()).anyMatch(t -> t.name().equals(type)));
        if (!validType) {
            throw new IllegalArgumentException("Unbekannter Anlass: " + type);
        }
        if (scope == Scope.GLOBAL && choice == Choice.DEFAULT) {
            throw new IllegalArgumentException("Global gibt es nur an oder aus.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("scope", scope.name())
                .addValue("id", scope == Scope.GLOBAL ? 0L : scopeId).addValue("type", type)
                .addValue("channel", channel.name());
        if (choice == Choice.DEFAULT) {
            jdbc.update("DELETE FROM notification_preferences WHERE scope = :scope AND scope_id = :id "
                    + "AND type = :type AND channel = :channel", params);
            return;
        }
        params.addValue("enabled", choice == Choice.ON);
        jdbc.update("""
                INSERT INTO notification_preferences (scope, scope_id, type, channel, enabled)
                VALUES (:scope, :id, :type, :channel, :enabled)
                ON CONFLICT (scope, scope_id, type, channel) DO UPDATE SET enabled = EXCLUDED.enabled""", params);
    }

    private Boolean lookup(Scope scope, Long scopeId, String type, Channel channel) {
        List<Boolean> values = jdbc.queryForList("SELECT enabled FROM notification_preferences WHERE scope = :scope "
                        + "AND scope_id = :id AND type = :type AND channel = :channel",
                Map.of("scope", scope.name(), "id", scopeId, "type", type, "channel", channel.name()), Boolean.class);
        return values.isEmpty() ? null : values.getFirst();
    }
}
