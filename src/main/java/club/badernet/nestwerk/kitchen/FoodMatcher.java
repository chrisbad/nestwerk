package club.badernet.nestwerk.kitchen;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ordnet einem Zutatennamen ein Lebensmittel zu: erst der ganze Name (auch in Einzahl), dann einzelne Wörter, dann das
 * längste Lebensmittel (ab 4 Zeichen), dessen Name im Text steckt („Knoblauchzehen“ → Knoblauch).
 */
@Component
public class FoodMatcher {

    private static final String[] SUFFIXES = {"", "n", "en", "e", "s", "er", "nen"};
    private static final int MIN_CONTAINED = 4;

    private final FoodRepository foods;

    public FoodMatcher(FoodRepository foods) {
        this.foods = foods;
    }

    public Optional<Food> match(String name) {
        return best(name, foods.findAll());
    }

    public static Optional<Food> best(String name, Collection<Food> candidates) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String text = normalize(name.replaceAll("\\(.*?\\)", " "));
        Map<String, Food> byName = new LinkedHashMap<>();
        candidates.forEach(f -> byName.putIfAbsent(normalize(f.getName()), f));

        Optional<Food> whole = singular(text, byName);
        if (whole.isPresent()) {
            return whole;
        }
        List<String> words = new ArrayList<>(List.of(text.split(" ")));
        java.util.Collections.reverse(words);
        for (String word : words) {
            Optional<Food> found = singular(word, byName);
            if (found.isPresent()) {
                return found;
            }
        }
        return byName.entrySet().stream()
                .filter(e -> e.getKey().length() >= MIN_CONTAINED && text.contains(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue);
    }

    private static Optional<Food> singular(String text, Map<String, Food> byName) {
        for (String suffix : SUFFIXES) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                Food f = byName.get(text.substring(0, text.length() - suffix.length()));
                if (f != null) {
                    return Optional.of(f);
                }
            }
        }
        return Optional.empty();
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.GERMAN).replaceAll("[^\\p{L}\\p{N} -]", " ").replaceAll("\\s+", " ").strip();
    }
}
