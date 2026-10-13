package club.badernet.nestwerk.security;

public enum AdminPermission implements Permission {
    USER_MANAGE("Benutzer verwalten"),
    ACCESS_ROLE_MANAGE("Rollen und Rechte verwalten"),
    MEMBER_MANAGE("Familienmitglieder verwalten");

    private final String label;

    AdminPermission(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String module() {
        return "Verwaltung";
    }
}
