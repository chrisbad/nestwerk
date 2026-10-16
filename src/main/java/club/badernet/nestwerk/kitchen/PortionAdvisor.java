package club.badernet.nestwerk.kitchen;

/** Portionsvorschlag: Kalorienziel × Anteil der Mahlzeit ÷ kcal je Portion, auf Viertel gerundet, 0,5 bis 3. */
public final class PortionAdvisor {

    static final double MIN = 0.5;
    static final double MAX = 3.0;

    private PortionAdvisor() {
    }

    public static double suggest(Integer goal, int sharePercent, Double kcalPerServing) {
        if (goal == null || kcalPerServing == null || kcalPerServing <= 0 || sharePercent <= 0) {
            return 1.0;
        }
        double raw = goal * sharePercent / 100.0 / kcalPerServing;
        double rounded = Math.round(raw * 4) / 4.0;
        return Math.max(MIN, Math.min(MAX, rounded));
    }
}
