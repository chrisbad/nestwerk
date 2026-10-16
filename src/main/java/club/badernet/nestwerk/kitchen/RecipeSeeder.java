package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.notification.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Legt beim ersten Start den Rezept-Grundstock aus {@code kitchen/recipes-seed.md} an. Danach merkt sich
 * {@code kitchen.recipesSeeded}, dass es erledigt ist – gelöschte oder geänderte Rezepte kommen nicht wieder.
 */
@Component
public class RecipeSeeder implements ApplicationRunner {

    static final String MARKER = "kitchen.recipesSeeded";
    static final String RESOURCE = "kitchen/recipes-seed.md";

    private static final Logger log = LoggerFactory.getLogger(RecipeSeeder.class);

    private final RecipeService recipes;
    private final RecipeRepository repository;
    private final AppSettings settings;
    private final boolean enabled;

    public RecipeSeeder(RecipeService recipes, RecipeRepository repository, AppSettings settings,
                        @Value("${nestwerk.kitchen.seed-recipes:true}") boolean enabled) {
        this.recipes = recipes;
        this.repository = repository;
        this.settings = settings;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || settings.get(MARKER).isPresent()) {
            return;
        }
        List<RecipeInput> seed;
        try {
            seed = RecipeSeedParser.parse(new String(new ClassPathResource(RESOURCE).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("Rezept-Grundstock konnte nicht gelesen werden: {}", e.getMessage());
            return;
        }
        Set<String> existing = new HashSet<>();
        repository.findAll().forEach(r -> existing.add(r.getTitle().toLowerCase(Locale.GERMAN)));
        int created = 0;
        for (RecipeInput input : seed) {
            if (existing.contains(input.title().toLowerCase(Locale.GERMAN))) {
                continue;
            }
            try {
                recipes.createUnchecked(input, null);
                created++;
            } catch (RuntimeException e) {
                log.warn("Rezept „{}“ im Grundstock übersprungen: {}", input.title(), e.getMessage());
            }
        }
        settings.set(MARKER, String.valueOf(created));
        log.info("Rezept-Grundstock geladen: {} Rezepte.", created);
    }
}
