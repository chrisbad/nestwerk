package club.badernet.nestwerk.kitchen;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Mengen für die Anzeige: deutsch, ohne überflüssige Nachkommastellen. */
public final class Amounts {

    private Amounts() {
    }

    public static String number(double value) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY));
        return format.format(value);
    }

    public static String format(Double amount, Unit unit) {
        if (amount == null) {
            return unit == null ? "" : unit.getLabel();
        }
        return number(amount) + (unit == null ? "" : " " + unit.getLabel());
    }
}
