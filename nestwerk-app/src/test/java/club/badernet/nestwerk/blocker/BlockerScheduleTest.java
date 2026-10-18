package club.badernet.nestwerk.blocker;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockerScheduleTest {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    static Instant at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    static Blocker blocker(LocalTime start, LocalTime end, Set<DayOfWeek> days, boolean holidaysOff) {
        return new Blocker("Test", BlockerKind.WORK, start, end, days, LocalDate.of(2026, 1, 1), null,
                BlockerStrength.WARN, BlockerVisibility.TITLE, false, holidaysOff, null);
    }

    static final Set<DayOfWeek> WORKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

    @Test
    void workdaysGiveFiveSlotsAWeek() {
        Blocker work = blocker(LocalTime.of(8, 0), LocalTime.of(16, 0), WORKDAYS, false);

        List<BlockerSlot> slots = BlockerSchedule.slots(List.of(work), at(10, 12, 0, 0), at(10, 19, 0, 0), BERLIN, null);

        assertThat(slots).hasSize(5);
        assertThat(slots.getFirst().start()).isEqualTo(at(10, 12, 8, 0));
        assertThat(slots.getFirst().end()).isEqualTo(at(10, 12, 16, 0));
        assertThat(slots.getFirst().day()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void sleepEndsNextMorningAcrossMonthEnd() {
        Blocker sleep = blocker(LocalTime.of(22, 0), LocalTime.of(6, 30), EnumSet.allOf(DayOfWeek.class), false);

        List<BlockerSlot> slots = BlockerSchedule.slots(List.of(sleep), at(10, 31, 12, 0), at(11, 1, 12, 0), BERLIN, null);

        assertThat(slots).singleElement().satisfies(s -> {
            assertThat(s.start()).isEqualTo(at(10, 31, 22, 0));
            assertThat(s.end()).isEqualTo(at(11, 1, 6, 30));
        });
    }

    @Test
    void slotStartingBeforeTheWindowIsIncluded() {
        Blocker sleep = blocker(LocalTime.of(22, 0), LocalTime.of(6, 30), EnumSet.allOf(DayOfWeek.class), false);

        List<BlockerSlot> slots = BlockerSchedule.slots(List.of(sleep), at(10, 14, 5, 0), at(10, 14, 7, 0), BERLIN, null);

        assertThat(slots).singleElement().extracting(BlockerSlot::start).isEqualTo(at(10, 13, 22, 0));
    }

    @Test
    void skippedDaysValidityAndHolidaysAreLeftOut() {
        Blocker work = blocker(LocalTime.of(8, 0), LocalTime.of(16, 0), WORKDAYS, true);
        work.setSkips(Set.of(LocalDate.of(2026, 10, 13)));
        Blocker until = new Blocker("Kurz", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(9, 0), WORKDAYS,
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14), BlockerStrength.WARN,
                BlockerVisibility.TITLE, false, false, null);

        // Fronleichnam (4.6.2026) ist in Bayern Feiertag: nur Blocker mit „an Feiertagen frei“ entfallen
        List<BlockerSlot> june = BlockerSchedule.slots(List.of(work), at(6, 1, 0, 0), at(6, 8, 0, 0), BERLIN, "BY");
        assertThat(june).extracting(BlockerSlot::day).doesNotContain(LocalDate.of(2026, 6, 4)).hasSize(4);
        List<BlockerSlot> withoutHolidayRule = BlockerSchedule.slots(
                List.of(blocker(LocalTime.of(8, 0), LocalTime.of(16, 0), WORKDAYS, false)),
                at(6, 1, 0, 0), at(6, 8, 0, 0), BERLIN, "BY");
        assertThat(withoutHolidayRule).hasSize(5);

        List<BlockerSlot> october = BlockerSchedule.slots(List.of(work, until), at(10, 12, 0, 0), at(10, 19, 0, 0),
                BERLIN, null);
        assertThat(october).filteredOn(s -> s.blocker() == work).extracting(BlockerSlot::day)
                .doesNotContain(LocalDate.of(2026, 10, 13)).hasSize(4);
        assertThat(october).filteredOn(s -> s.blocker() == until).extracting(BlockerSlot::day)
                .containsExactly(LocalDate.of(2026, 10, 14));
    }

    @Test
    void localTimesSurviveDaylightSavingChange() {
        Blocker sleep = blocker(LocalTime.of(22, 0), LocalTime.of(6, 30), EnumSet.allOf(DayOfWeek.class), false);

        List<BlockerSlot> slots = BlockerSchedule.slots(List.of(sleep), at(10, 24, 12, 0), at(10, 25, 12, 0), BERLIN, null);

        // 24.10. 22:00 Sommerzeit bis 25.10. 06:30 Winterzeit: 9,5 Stunden
        assertThat(slots).singleElement().satisfies(s ->
                assertThat(java.time.Duration.between(s.start(), s.end()).toMinutes()).isEqualTo(570));
    }
}
