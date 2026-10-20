package club.badernet.nestwerk.calendar;

import java.util.Arrays;
import java.util.Locale;

/**
 * Fixed palette for event categories. The tones are saturated enough to stay distinguishable as tinted backgrounds
 * in light and dark mode while the text on top keeps the regular ink colour.
 */
public enum CategoryColor {
    RED("Rot", "#e5484d"),
    ORANGE("Orange", "#f76b15"),
    AMBER("Gelb", "#e2a336"),
    GREEN("Grün", "#30a46c"),
    TEAL("Türkis", "#12a594"),
    BLUE("Blau", "#0090ff"),
    INDIGO("Indigo", "#3e63dd"),
    VIOLET("Violett", "#8e4ec6"),
    PINK("Pink", "#d6409f"),
    BROWN("Braun", "#a18072"),
    SLATE("Grau", "#8b8d98");

    private final String label;
    private final String hex;

    CategoryColor(String label, String hex) {
        this.label = label;
        this.hex = hex;
    }

    public String label() {
        return label;
    }

    public String hex() {
        return hex;
    }

    /** Parses a palette name case-insensitively, e.g. {@code blue}. */
    public static CategoryColor parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bitte eine Farbe wählen.");
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Farbe „" + value + "“. Erlaubt: "
                        + String.join(", ", Arrays.stream(values()).map(Enum::name).toList()) + "."));
    }
}
