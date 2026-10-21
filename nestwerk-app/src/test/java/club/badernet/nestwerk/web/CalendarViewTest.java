package club.badernet.nestwerk.web;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarViewTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);

    @Test
    void weekShowsTheWholeWeekOfTheFocusDayAndPagesByWeek() {
        assertThat(CalendarView.WEEK.days(WEDNESDAY)).first().isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(CalendarView.WEEK.days(WEDNESDAY)).hasSize(7);
        assertThat(CalendarView.WEEK.next(WEDNESDAY)).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void threeDaysStartAtTheFocusDay() {
        assertThat(CalendarView.THREE_DAYS.days(WEDNESDAY))
                .containsExactly(WEDNESDAY, WEDNESDAY.plusDays(1), WEDNESDAY.plusDays(2));
        assertThat(CalendarView.THREE_DAYS.previous(WEDNESDAY)).isEqualTo(WEDNESDAY.minusDays(3));
    }

    @Test
    void monthKeepsTheDayWherePossible() {
        assertThat(CalendarView.MONTH.next(WEDNESDAY)).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(CalendarView.MONTH.next(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(CalendarView.MONTH.days(WEDNESDAY)).contains(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }

    @Test
    void myDayShowsOnlyTheFocusDayAndPagesByDay() {
        assertThat(CalendarView.MY_DAY.days(WEDNESDAY)).containsExactly(WEDNESDAY);
        assertThat(CalendarView.MY_DAY.next(WEDNESDAY)).isEqualTo(WEDNESDAY.plusDays(1));
        assertThat(CalendarView.MY_DAY.path()).isEqualTo("my");
    }

    @Test
    void dayAndAgendaPageByTheirLength() {
        assertThat(CalendarView.DAY.days(WEDNESDAY)).containsExactly(WEDNESDAY);
        assertThat(CalendarView.DAY.previous(WEDNESDAY)).isEqualTo(WEDNESDAY.minusDays(1));
        assertThat(CalendarView.AGENDA.days(WEDNESDAY)).hasSize(CalendarView.AGENDA_DAYS);
        assertThat(CalendarView.AGENDA.next(WEDNESDAY)).isEqualTo(WEDNESDAY.plusDays(CalendarView.AGENDA_DAYS));
    }
}
