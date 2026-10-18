package club.badernet.nestwerk.security.acl;

/** Eine Zugriffsregel bzw. Zelle der Matrix: Aktion auf Objekttyp bei Beziehung. */
public record AclRule(String objectType, String action, String relation) {

    /** Liest das Format {@code TYP:AKTION:BEZIEHUNG}, wie es die Matrix sendet. */
    public static AclRule parse(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Ungültige Zugriffsregel: " + value);
        }
        return new AclRule(parts[0], parts[1], parts[2]);
    }

    public String key() {
        return objectType + ":" + action + ":" + relation;
    }
}
