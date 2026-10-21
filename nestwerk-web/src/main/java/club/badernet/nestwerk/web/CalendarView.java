package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarGrid;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The views of the calendar and the period each of them shows around the focus day. The focus day stays the same
 * when switching between views, so day → week → day returns to the same day.
 */
public enum CalendarView {

    MY_DAY("my") {
        @Override
        public List<LocalDate> days(LocalDate focus) {
            return List.of(focus);
        }

        @Override
        public LocalDate shift(LocalDate focus, int periods) {
            return focus.plusDays(periods);
        }
    },
    DAY("day") {
        @Override
        public List<LocalDate> days(LocalDate focus) {
            return List.of(focus);
        }

        @Override
        public LocalDate shift(LocalDate focus, int periods) {
            return focus.plusDays(periods);
        }
    },
    THREE_DAYS("days3") {
        @Override
        public List<LocalDate> days(LocalDate focus) {
            return focus.datesUntil(focus.plusDays(THREE)).toList();
        }

        @Override
        public LocalDate shift(LocalDate focus, int periods) {
            return focus.plusDays((long) THREE * periods);
        }
    },
    WEEK("week") {
        @Override
        public List<LocalDate> days(LocalDate focus) {
            return CalendarGrid.weekDays(focus);
        }

        @Override
        public LocalDate shift(LocalDate focus, int periods) {
            return focus.plusWeeks(periods);
        }
    },
    MONTH("month") {
        @Override
        public List<LocalDate> days(LocalDate focus) {
            return CalendarGrid.monthDays(YearMonth.from(focus));
        }

        @Override
        public LocalDate shift(LocalDate focus, int periods) {
            return focus.plusMonths(periods);
        }
    },
    AGENDA("agenda") {
        @Override
        public List<LocalDate> days(LocalDate focus) {
            return focus.datesUntil(focus.plusDays(AGENDA_DAYS)).toList();
        }

        @Override
        public LocalDate shift(LocalDate focus, int periods) {
            return focus.plusDays((long) AGENDA_DAYS * periods);
        }
    };

    private static final int THREE = 3;
    static final int AGENDA_DAYS = 60;

    private final String path;

    CalendarView(String path) {
        this.path = path;
    }

    /** The path segment below {@code /calendar} and the view name used by templates and scripts. */
    public String path() {
        return path;
    }

    /** The days the view shows for the focus day. */
    public abstract List<LocalDate> days(LocalDate focus);

    /** The focus day {@code periods} periods later (negative: earlier); months keep the day where possible. */
    public abstract LocalDate shift(LocalDate focus, int periods);

    public LocalDate previous(LocalDate focus) {
        return shift(focus, -1);
    }

    public LocalDate next(LocalDate focus) {
        return shift(focus, 1);
    }
}
