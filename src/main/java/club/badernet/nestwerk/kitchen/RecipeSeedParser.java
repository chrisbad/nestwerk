package club.badernet.nestwerk.kitchen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Liest die Rezeptdatei des Grundstocks. Ein Rezept beginnt mit {@code # Titel}, dann eine Zeile
 * {@code portionen: 4 | minuten: 30 | tags: a, b}, der Abschnitt {@code ## Zutaten} mit einer Zutat je Zeile und der
 * Abschnitt {@code ## Schritte}.
 */
public final class RecipeSeedParser {

    private RecipeSeedParser() {
    }

    public static List<RecipeInput> parse(String text) {
        List<RecipeInput> result = new ArrayList<>();
        String title = null;
        Integer servings = null;
        Integer minutes = null;
        String tags = null;
        List<String> ingredients = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        String section = null;

        for (String raw : (text == null ? "" : text).split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("# ")) {
                add(result, title, servings, minutes, tags, ingredients, steps);
                title = line.substring(2).strip();
                servings = null;
                minutes = null;
                tags = null;
                ingredients = new ArrayList<>();
                steps = new ArrayList<>();
                section = "meta";
            } else if (line.startsWith("## ")) {
                section = line.substring(3).strip().toLowerCase(Locale.GERMAN);
            } else if (line.isEmpty() || title == null) {
                continue;
            } else if ("meta".equals(section)) {
                for (String part : line.split("\\|")) {
                    int colon = part.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String key = part.substring(0, colon).strip().toLowerCase(Locale.GERMAN);
                    String value = part.substring(colon + 1).strip();
                    switch (key) {
                        case "portionen" -> servings = Integer.valueOf(value);
                        case "minuten" -> minutes = Integer.valueOf(value);
                        case "tags" -> tags = value;
                        default -> throw new IllegalArgumentException("Unbekannter Eintrag „" + key + "“ bei „" + title + "“");
                    }
                }
            } else if ("zutaten".equals(section)) {
                ingredients.add(line);
            } else if ("schritte".equals(section)) {
                steps.add(line);
            }
        }
        add(result, title, servings, minutes, tags, ingredients, steps);
        return result;
    }

    private static void add(List<RecipeInput> result, String title, Integer servings, Integer minutes, String tags,
                            List<String> ingredients, List<String> steps) {
        if (title == null) {
            return;
        }
        result.add(new RecipeInput(title, null, servings, minutes, tags, null, String.join("\n", steps),
                RecipeService.parseLines(String.join("\n", ingredients))));
    }
}
