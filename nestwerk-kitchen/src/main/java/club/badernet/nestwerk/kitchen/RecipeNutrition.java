package club.badernet.nestwerk.kitchen;

import java.util.List;

/** Nährwerte eines Rezepts; {@code missing} nennt Zutaten, die mangels Angaben nicht mitgezählt wurden. */
public record RecipeNutrition(Nutrition total, Nutrition perServing, List<String> missing) {

    public boolean complete() {
        return missing.isEmpty();
    }
}
