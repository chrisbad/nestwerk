package club.badernet.nestwerk.kitchen;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zerlegt Zeilen wie „200 g Mehl“, „1 ½ Zwiebeln“, „1-2 Knoblauchzehen“ oder „Salz“. */
public final class IngredientParser {

    private static final Map<Character, Double> FRACTIONS = Map.of('½', 0.5, '¼', 0.25, '¾', 0.75, '⅓', 1.0 / 3,
            '⅔', 2.0 / 3, '⅛', 0.125);

    private static final String NUMBER = "(?:\\d+\\s*[½¼¾⅓⅔⅛]|\\d+(?:[.,]\\d+)?(?:\\s*/\\s*\\d+)?|[½¼¾⅓⅔⅛])";
    private static final Pattern AMOUNT = Pattern.compile("^(" + NUMBER + ")(?:\\s*[-–]\\s*" + NUMBER + ")?\\s*(.*)$");

    private static final Map<String, Unit> UNITS = Map.ofEntries(
            Map.entry("g", Unit.G), Map.entry("gr", Unit.G), Map.entry("gramm", Unit.G),
            Map.entry("kg", Unit.KG), Map.entry("kilo", Unit.KG),
            Map.entry("ml", Unit.ML), Map.entry("l", Unit.L), Map.entry("liter", Unit.L),
            Map.entry("stück", Unit.PIECE), Map.entry("stk", Unit.PIECE), Map.entry("stk.", Unit.PIECE),
            Map.entry("el", Unit.TBSP), Map.entry("esslöffel", Unit.TBSP),
            Map.entry("tl", Unit.TSP), Map.entry("teelöffel", Unit.TSP),
            Map.entry("prise", Unit.PINCH), Map.entry("prisen", Unit.PINCH),
            Map.entry("packung", Unit.PACKAGE), Map.entry("packungen", Unit.PACKAGE), Map.entry("pck", Unit.PACKAGE),
            Map.entry("pck.", Unit.PACKAGE), Map.entry("pkg", Unit.PACKAGE), Map.entry("pkg.", Unit.PACKAGE),
            Map.entry("päckchen", Unit.PACKAGE),
            Map.entry("bund", Unit.BUNCH),
            Map.entry("dose", Unit.CAN), Map.entry("dosen", Unit.CAN),
            Map.entry("scheibe", Unit.SLICE), Map.entry("scheiben", Unit.SLICE));

    private static final Pattern GLUED_UNIT = Pattern.compile("^(g|kg|ml|l)\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private IngredientParser() {
    }

    /** {@code null} für leere Zeilen. */
    public static IngredientLine parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String text = line.strip().replaceAll("\\s+", " ");
        Double amount = null;
        String rest = text;
        Matcher m = AMOUNT.matcher(text);
        if (m.matches()) {
            amount = number(m.group(1));
            rest = m.group(2);
        }
        Unit unit = null;
        Matcher glued = GLUED_UNIT.matcher(rest);
        String firstWord = rest.contains(" ") ? rest.substring(0, rest.indexOf(' ')) : rest;
        Unit word = UNITS.get(firstWord.toLowerCase(Locale.GERMAN));
        if (word != null && !firstWord.equals(rest)) {
            unit = word;
            rest = rest.substring(firstWord.length()).strip();
        } else if (amount != null && glued.matches() && !glued.group(2).isEmpty()) {
            unit = UNITS.get(glued.group(1).toLowerCase(Locale.ROOT));
            rest = glued.group(2);
        }
        if (unit != null && amount == null) {
            amount = 1.0;
        } else if (unit == null && amount != null) {
            unit = Unit.PIECE;
        }
        String note = null;
        int comma = rest.indexOf(',');
        if (comma > 0) {
            note = rest.substring(comma + 1).strip();
            rest = rest.substring(0, comma).strip();
            if (note.isEmpty()) {
                note = null;
            }
        }
        return new IngredientLine(amount, unit, rest, note, text);
    }

    static double number(String raw) {
        String s = raw.replace(" ", "");
        double value = 0;
        char last = s.charAt(s.length() - 1);
        if (FRACTIONS.containsKey(last)) {
            value = FRACTIONS.get(last);
            s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) {
                return value;
            }
        }
        if (s.contains("/")) {
            String[] parts = s.split("/");
            return value + Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        }
        return value + Double.parseDouble(s.replace(',', '.'));
    }
}
