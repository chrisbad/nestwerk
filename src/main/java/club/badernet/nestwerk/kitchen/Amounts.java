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

    /** Für Eingabefelder: ohne Tausenderpunkte, Komma als Dezimalzeichen. */
    public static String plain(double value) {
        DecimalFormat format = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.GERMANY));
        return format.format(value);
    }

    /** Liest „1,5“, „1.5“ und „1.000,5“. */
    public static double parse(String raw) {
        String s = raw.strip();
        if (s.contains(",")) {
            s = s.replace(".", "").replace(',', '.');
        }
        return Double.parseDouble(s);
    }

    public static String format(Double amount, Unit unit) {
        if (amount == null) {
            return unit == null ? "" : unit.getLabel();
        }
        return number(amount) + (unit == null ? "" : " " + unit.getLabel());
    }
}
