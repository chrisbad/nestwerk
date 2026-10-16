package club.badernet.nestwerk.kitchen;

/** Abteilung im Laden; ordnet Einkaufsliste und Vorrat. */
public enum FoodCategory {
    PRODUCE("Obst & Gemüse"),
    BAKERY("Brot & Backwaren"),
    CHILLED("Kühlregal"),
    MEAT_FISH("Fleisch & Fisch"),
    PANTRY("Vorrat"),
    FROZEN("Tiefkühl"),
    DRINKS("Getränke"),
    SPICES("Gewürze"),
    OTHER("Sonstiges");

    private final String label;

    FoodCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
