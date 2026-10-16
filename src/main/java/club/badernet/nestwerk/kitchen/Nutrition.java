package club.badernet.nestwerk.kitchen;

/** Nährwerte einer Menge: kcal und Gramm Eiweiß, Fett, Kohlenhydrate. */
public record Nutrition(double kcal, double protein, double fat, double carbs) {

    public static final Nutrition ZERO = new Nutrition(0, 0, 0, 0);

    public static Nutrition of(Food food, double grams) {
        double f = grams / 100.0;
        return new Nutrition(value(food.getKcal()) * f, value(food.getProtein()) * f, value(food.getFat()) * f,
                value(food.getCarbs()) * f);
    }

    public Nutrition plus(Nutrition other) {
        return new Nutrition(kcal + other.kcal, protein + other.protein, fat + other.fat, carbs + other.carbs);
    }

    public Nutrition times(double factor) {
        return new Nutrition(kcal * factor, protein * factor, fat * factor, carbs * factor);
    }

    private static double value(Double v) {
        return v == null ? 0 : v;
    }
}
