package club.badernet.nestwerk.security;

/** Ein Eintrag aus der Tabelle {@code permissions}. */
public record PermissionDefinition(String key, String module, String label, String description,
                                   int sortOrder, boolean system) {
}
