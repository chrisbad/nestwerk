package club.badernet.nestwerk.transfer;

import java.util.List;

/** Ergebnis einer Import-Vorschau oder eines Imports: eine Zeile je Datensatz der Datei. */
public record ImportReport(List<Row> rows, List<String> notes, boolean applied) {

    public enum Action {
        NEW("neu"), UPDATE("aktualisiert"), SKIP("übersprungen");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Ein Datensatz: Bezeichnung, kurzer Inhalt, was passiert und ggf. warum nicht. */
    public record Row(String label, String detail, Action action, String problem) {

        public static Row skip(String label, String detail, String problem) {
            return new Row(label, detail, Action.SKIP, problem);
        }
    }

    public long count(Action action) {
        return rows.stream().filter(r -> r.action() == action).count();
    }

    public long getNewCount() {
        return count(Action.NEW);
    }

    public long getUpdateCount() {
        return count(Action.UPDATE);
    }

    public long getSkipCount() {
        return count(Action.SKIP);
    }
}
