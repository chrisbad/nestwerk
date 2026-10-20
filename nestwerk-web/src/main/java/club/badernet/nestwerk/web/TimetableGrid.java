package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.school.SchoolPeriod;
import club.badernet.nestwerk.school.Timetable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Week view of the timetable as a CSS grid: one column per school day, one row per period or break of the school's
 * grid. Lessons span the rows of their periods; lessons with free times go to an extra row at the bottom. Without a
 * grid, the rows come from the start times of the lessons.
 */
public record TimetableGrid(List<Column> columns, List<Row> rows, List<Cell> cells, List<Slot> slots,
                            boolean usesPeriods) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** The first grid row holds the day headers. */
    private static final int FIRST_ROW = 2;
    /** The first grid column holds the row headers. */
    private static final int FIRST_COLUMN = 2;

    /** A school day; {@code free} names holidays or public holidays. */
    public record Column(LocalDate date, String name, boolean today, String free, int column) {
    }

    /** A period, a break or the extra row. */
    public record Row(String label, String times, boolean pause, boolean extra, int row) {
    }

    /** A lesson placed in the grid. */
    public record Cell(Timetable.Entry entry, List<Contact> teachers, LocalDate date, int column, int row,
                       int rowEnd) {

        public String times() {
            return TIME.format(entry.lesson().getStartsAt()) + "–" + TIME.format(entry.lesson().getEndsAt());
        }

        /** Short names of the teachers for the cell, e.g. "Weber, Kaya". */
        public String teacherNames() {
            return String.join(", ", teachers.stream().map(Contact::getName).toList());
        }
    }

    /** An empty period in which a lesson can be added. */
    public record Slot(DayOfWeek weekday, Integer period, int column, int row) {
    }

    /** Lessons of the day sorted by start, per date of the shown days. */
    public static TimetableGrid build(List<LocalDate> days, LocalDate today, Map<LocalDate, String> free,
                                      Map<LocalDate, List<Timetable.Entry>> entries, List<SchoolPeriod> grid,
                                      Map<Long, List<Contact>> teachers) {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i);
            columns.add(new Column(day, day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                    day.equals(today), free.get(day), FIRST_COLUMN + i));
        }
        return grid.isEmpty() ? byStartTimes(columns, entries, teachers)
                : byPeriods(columns, entries, grid, teachers);
    }

    private static TimetableGrid byPeriods(List<Column> columns, Map<LocalDate, List<Timetable.Entry>> entries,
                                           List<SchoolPeriod> grid, Map<Long, List<Contact>> teachers) {
        List<Row> rows = new ArrayList<>();
        Map<Integer, Integer> rowOfPeriod = new HashMap<>();
        int row = FIRST_ROW;
        for (SchoolPeriod period : grid) {
            rows.add(new Row(period.isBreak() ? period.displayName() : period.getNumber() + ".",
                    TIME.format(period.getStartsAt()) + "–" + TIME.format(period.getEndsAt()), period.isBreak(),
                    false, row));
            if (!period.isBreak()) {
                rowOfPeriod.put(period.getNumber(), row);
            }
            row++;
        }
        int extraRow = row;
        List<Cell> cells = new ArrayList<>();
        List<Cell> extras = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        for (Column column : columns) {
            if (column.free() != null) {
                continue;
            }
            Set<Integer> taken = new HashSet<>();
            for (Timetable.Entry entry : entries.getOrDefault(column.date(), List.of())) {
                Integer from = entry.lesson().getPeriodFrom();
                Integer to = entry.lesson().getPeriodTo();
                if (from != null && rowOfPeriod.containsKey(from) && rowOfPeriod.containsKey(to)) {
                    cells.add(new Cell(entry, teachersOf(entry, teachers), column.date(), column.column(),
                            rowOfPeriod.get(from), rowOfPeriod.get(to) + 1));
                    for (int p = from; p <= to; p++) {
                        taken.add(p);
                    }
                } else {
                    extras.add(new Cell(entry, teachersOf(entry, teachers), column.date(), column.column(),
                            extraRow, extraRow + 1));
                }
            }
            rowOfPeriod.forEach((period, periodRow) -> {
                if (!taken.contains(period)) {
                    slots.add(new Slot(column.date().getDayOfWeek(), period, column.column(), periodRow));
                }
            });
        }
        if (!extras.isEmpty()) {
            rows.add(new Row("Weitere", "", false, true, extraRow));
            cells.addAll(extras);
        }
        return new TimetableGrid(columns, rows, cells, slots, true);
    }

    private static TimetableGrid byStartTimes(List<Column> columns, Map<LocalDate, List<Timetable.Entry>> entries,
                                              Map<Long, List<Contact>> teachers) {
        TreeSet<LocalTime> starts = new TreeSet<>();
        columns.stream().filter(c -> c.free() == null)
                .forEach(c -> entries.getOrDefault(c.date(), List.of())
                        .forEach(e -> starts.add(e.lesson().getStartsAt())));
        Map<LocalTime, Integer> rowOfStart = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        int row = FIRST_ROW;
        for (LocalTime start : starts) {
            rows.add(new Row(TIME.format(start), "", false, false, row));
            rowOfStart.put(start, row++);
        }
        List<Cell> cells = new ArrayList<>();
        for (Column column : columns) {
            if (column.free() != null) {
                continue;
            }
            for (Timetable.Entry entry : entries.getOrDefault(column.date(), List.of())) {
                int r = rowOfStart.get(entry.lesson().getStartsAt());
                cells.add(new Cell(entry, teachersOf(entry, teachers), column.date(), column.column(), r, r + 1));
            }
        }
        return new TimetableGrid(columns, rows, cells, List.of(), false);
    }

    private static List<Contact> teachersOf(Timetable.Entry entry, Map<Long, List<Contact>> teachers) {
        return teachers.getOrDefault(entry.subject().getId(), List.of());
    }

    /** Value of {@code grid-template-rows}: day headers, then periods with narrow breaks. */
    public String rowTemplate() {
        StringBuilder template = new StringBuilder("auto");
        for (Row row : rows) {
            template.append(row.pause() ? " minmax(1.5rem, auto)" : row.extra() ? " auto" : " minmax(3.75rem, auto)");
        }
        return template.toString();
    }

    /** Value of {@code grid-template-columns}: row headers and one column per day. */
    public String columnTemplate() {
        return "minmax(3.5rem, auto) repeat(" + columns.size() + ", minmax(0, 1fr))";
    }

    /** Last grid row (exclusive) for overlays that cover a whole day. */
    public int lastRow() {
        return Math.max(FIRST_ROW + 1, rows.isEmpty() ? FIRST_ROW + 1 : rows.getLast().row() + 1);
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Row of lessons with free times, if any. */
    public Row extraRow() {
        return rows.stream().filter(Row::extra).findFirst().orElse(null);
    }

    /** Lessons placed in periods or start-time rows. */
    public List<Cell> mainCells() {
        Row extra = extraRow();
        return extra == null ? cells : cells.stream().filter(c -> c.row() != extra.row()).toList();
    }

    /** Lessons with free times of one column, stacked in the extra row. */
    public List<Cell> extraCells(int column) {
        Row extra = extraRow();
        return extra == null ? List.of()
                : cells.stream().filter(c -> c.row() == extra.row() && c.column() == column).toList();
    }

    /** Cells of one day ordered by start, for the phone layout. */
    public List<Cell> cellsOf(LocalDate date) {
        return cells.stream().filter(c -> c.date().equals(date))
                .sorted(java.util.Comparator.comparing(c -> c.entry().lesson().getStartsAt())).toList();
    }
}
