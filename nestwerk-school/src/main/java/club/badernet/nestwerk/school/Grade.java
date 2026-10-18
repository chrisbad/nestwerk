package club.badernet.nestwerk.school;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schulnoten von 1 bis 6, gespeichert in Zehnteln (17 = 1,7). Eingabe mit Tendenz: {@code 2+} ist 1,7, {@code 2-} ist
 * 2,3; {@code 1+} und {@code 6-} bleiben bei 1,0 und 6,0. Dezimalwerte wie {@code 2,5} sind ebenfalls erlaubt.
 */
public final class Grade {

    public static final int MIN = 10;
    public static final int MAX = 60;

    private static final Pattern TREND = Pattern.compile("^([1-6])\\s*([+\\-−–])?$");
    private static final Pattern DECIMAL = Pattern.compile("^([1-6])[.,](\\d)$");

    private Grade() {
    }

    /** Liest eine Note wie {@code 2+}, {@code 3-}, {@code 4} oder {@code 2,5} in Zehnteln. */
    public static int parse(String text) {
        String clean = text == null ? "" : text.trim();
        Matcher trend = TREND.matcher(clean);
        if (trend.matches()) {
            int tenths = Integer.parseInt(trend.group(1)) * 10;
            String sign = trend.group(2);
            if (sign != null) {
                tenths += "+".equals(sign) ? -3 : 3;
            }
            return Math.max(MIN, Math.min(MAX, tenths));
        }
        Matcher decimal = DECIMAL.matcher(clean);
        if (decimal.matches()) {
            int tenths = Integer.parseInt(decimal.group(1)) * 10 + Integer.parseInt(decimal.group(2));
            if (tenths >= MIN && tenths <= MAX) {
                return tenths;
            }
        }
        throw new IllegalArgumentException("Die Note muss zwischen 1 und 6 liegen, z. B. 2+, 3 oder 2,5.");
    }

    /** Anzeige: {@code 17} → {@code 2+}, {@code 23} → {@code 2-}, {@code 25} → {@code 2,5}. */
    public static String label(int tenths) {
        int base = Math.round(tenths / 10f);
        int diff = tenths - base * 10;
        if (diff == 0) {
            return String.valueOf(base);
        }
        if (diff == -3 && base >= 2) {
            return base + "+";
        }
        if (diff == 3 && base <= 5) {
            return base + "-";
        }
        return String.format(Locale.GERMAN, "%.1f", tenths / 10.0);
    }

    /** Durchschnitt mit zwei Nachkommastellen für die Anzeige, z. B. {@code 2,33}. */
    public static String format(double average) {
        return String.format(Locale.GERMAN, "%.2f", average);
    }
}
