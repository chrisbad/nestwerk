package club.badernet.nestwerk.kitchen;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Mengeneinheiten für Zutaten, Vorrat und Einkauf. */
public enum Unit {
    G("g"),
    KG("kg"),
    ML("ml"),
    L("l"),
    PIECE("Stück"),
    TBSP("EL"),
    TSP("TL"),
    PINCH("Prise"),
    PACKAGE("Packung"),
    BUNCH("Bund"),
    CAN("Dose"),
    SLICE("Scheibe");

    private final String label;

    Unit(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Einheit aus Name oder Anzeigename, ohne Groß/Klein, z. B. {@code "EL"}, {@code "stück"}, {@code "TBSP"}. */
    public static Optional<Unit> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.trim().toLowerCase(Locale.GERMAN);
        return Arrays.stream(values())
                .filter(u -> u.label.toLowerCase(Locale.GERMAN).equals(t) || u.name().toLowerCase(Locale.ROOT).equals(t))
                .findFirst();
    }
}
