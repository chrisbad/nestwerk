package club.badernet.nestwerk.todo;

/** Verweis auf einen Zuständigen; als Text {@code member:5}, {@code role:2} oder {@code contact:7}. */
public record AssigneeRef(Type type, Long id) {

    public enum Type { MEMBER, ROLE, CONTACT }

    public static AssigneeRef member(Long id) {
        return new AssigneeRef(Type.MEMBER, id);
    }

    public static AssigneeRef role(Long id) {
        return new AssigneeRef(Type.ROLE, id);
    }

    public static AssigneeRef contact(Long id) {
        return new AssigneeRef(Type.CONTACT, id);
    }

    public static AssigneeRef parse(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length == 2 && parts[1].matches("\\d{1,18}")) {
            Long id = Long.valueOf(parts[1]);
            switch (parts[0]) {
                case "member":
                    return member(id);
                case "role":
                    return role(id);
                case "contact":
                    return contact(id);
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("Ungültige Zuständigkeit: " + value);
    }

    public String key() {
        return type.name().toLowerCase() + ":" + id;
    }
}
