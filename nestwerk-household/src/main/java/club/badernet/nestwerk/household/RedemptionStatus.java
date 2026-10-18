package club.badernet.nestwerk.household;

public enum RedemptionStatus {
    REQUESTED("beantragt"),
    APPROVED("bestätigt"),
    REJECTED("abgelehnt");

    private final String label;

    RedemptionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
