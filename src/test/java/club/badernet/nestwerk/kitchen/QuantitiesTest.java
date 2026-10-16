package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuantitiesTest {

    static Food food(String name, Double kcal, Double piece, Double pack) {
        Food f = new Food(name, FoodCategory.OTHER, FoodSource.MANUAL);
        f.setNutrition(kcal, kcal == null ? null : 1.0, kcal == null ? null : 2.0, kcal == null ? null : 3.0);
        f.setPieceGrams(piece);
        f.setPackageGrams(pack);
        return f;
    }

    @Test
    void convertsMassVolumeAndSpoons() {
        Food oil = food("Öl", 884.0, null, null);
        assertThat(Quantities.grams(200, Unit.G, oil)).contains(200.0);
        assertThat(Quantities.grams(1.5, Unit.KG, oil)).contains(1500.0);
        assertThat(Quantities.grams(0.5, Unit.L, oil)).contains(500.0);
        assertThat(Quantities.grams(2, Unit.TBSP, oil)).contains(30.0);
        assertThat(Quantities.grams(1, Unit.TSP, oil)).contains(5.0);
        assertThat(Quantities.grams(2, Unit.PINCH, oil)).contains(1.0);
    }

    @Test
    void piecesNeedPieceWeightAndPackagesNeedPackageSize() {
        Food onion = food("Zwiebel", 40.0, 80.0, null);
        assertThat(Quantities.grams(2, Unit.PIECE, onion)).contains(160.0);
        assertThat(Quantities.grams(2, null, onion)).contains(160.0);
        assertThat(Quantities.grams(1, Unit.PACKAGE, onion)).isEmpty();
        Food tomatoes = food("Gehackte Tomaten", 21.0, null, 400.0);
        assertThat(Quantities.grams(2, Unit.CAN, tomatoes)).contains(800.0);
        assertThat(Quantities.grams(1, Unit.PIECE, tomatoes)).isEmpty();
    }

    @Test
    void convertsBetweenUnitsOverGrams() {
        Food onion = food("Zwiebel", 40.0, 80.0, null);
        assertThat(Quantities.convert(160, Unit.G, Unit.PIECE, onion)).contains(2.0);
        assertThat(Quantities.convert(3, Unit.PIECE, Unit.PIECE, null)).contains(3.0);
        assertThat(Quantities.convert(1, Unit.L, Unit.ML, null)).contains(1000.0);
        assertThat(Quantities.convert(1, Unit.PIECE, Unit.G, null)).isEmpty();
    }
}
