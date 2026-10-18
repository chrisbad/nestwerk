package club.badernet.nestwerk.kitchen;

import java.util.Optional;

/** Rechnet Mengen in Gramm um: Masse direkt, 1 ml = 1 g, Löffel und Prisen fest, Stück und Packung je Lebensmittel. */
public final class Quantities {

    public static final double TBSP_GRAMS = 15;
    public static final double TSP_GRAMS = 5;
    public static final double PINCH_GRAMS = 0.5;

    private Quantities() {
    }

    /** Gramm für eine Menge; ohne Einheit gilt „Stück“. Leer, wenn das Lebensmittel die Angabe zum Umrechnen fehlt. */
    public static Optional<Double> grams(double amount, Unit unit, Food food) {
        Optional<Double> perUnit = gramsPerUnit(unit == null ? Unit.PIECE : unit, food);
        return perUnit.map(g -> amount * g);
    }

    /** Menge in einer anderen Einheit; gleiche Einheit geht immer, sonst über Gramm. */
    public static Optional<Double> convert(double amount, Unit from, Unit to, Food food) {
        Unit source = from == null ? Unit.PIECE : from;
        Unit target = to == null ? Unit.PIECE : to;
        if (source == target) {
            return Optional.of(amount);
        }
        Optional<Double> grams = grams(amount, source, food);
        Optional<Double> perTarget = gramsPerUnit(target, food);
        if (grams.isEmpty() || perTarget.isEmpty() || perTarget.get() <= 0) {
            return Optional.empty();
        }
        return Optional.of(grams.get() / perTarget.get());
    }

    static Optional<Double> gramsPerUnit(Unit unit, Food food) {
        return switch (unit) {
            case G, ML -> Optional.of(1.0);
            case KG, L -> Optional.of(1000.0);
            case TBSP -> Optional.of(TBSP_GRAMS);
            case TSP -> Optional.of(TSP_GRAMS);
            case PINCH -> Optional.of(PINCH_GRAMS);
            case PIECE, BUNCH, SLICE -> Optional.ofNullable(food == null ? null : food.getPieceGrams());
            case PACKAGE, CAN -> Optional.ofNullable(food == null ? null : food.getPackageGrams());
        };
    }
}
