package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerRepository;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class HolidayServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    @Autowired HolidayService holidays;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired SchoolDays schoolDays;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired BlockerRepository blockers;
    @MockitoBean HolidayClient client;

    User admin;
    FamilyMember lena;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        lena = members.create("Lena", "#aabbcc", null);
    }

    private static HolidayClient.Entry entry(String id, String name, String from, String to) {
        return new HolidayClient.Entry(id, name, LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void refreshStoresOnlineEntriesAndReportsChanges() {
        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(
                entry("1", "Herbstferien", "2026-11-02", "2026-11-06"),
                entry("2", "Weihnachtsferien", "2026-12-24", "2027-01-08")));

        assertThat(holidays.refresh("BY", TODAY)).isTrue();
        assertThat(holidays.list("BY", TODAY, TODAY.plusYears(1))).extracting(SchoolHoliday::getName)
                .containsExactly("Herbstferien", "Weihnachtsferien");

        assertThat(holidays.refresh("BY", TODAY)).as("unverändert").isFalse();

        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(
                entry("1", "Herbstferien", "2026-11-02", "2026-11-07")));
        assertThat(holidays.refresh("BY", TODAY)).isTrue();
        assertThat(holidays.list("BY", TODAY, TODAY.plusYears(1))).extracting(SchoolHoliday::getEndsOn)
                .containsExactly(LocalDate.of(2026, 11, 7));
    }

    @Test
    void manualEntriesSurviveARefresh() {
        holidays.addManual("BY", "Brückentag", LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16), admin);
        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(
                entry("1", "Herbstferien", "2026-11-02", "2026-11-06")));

        holidays.refresh("BY", TODAY);

        assertThat(holidays.list("BY", TODAY, TODAY.plusYears(1))).extracting(SchoolHoliday::getName)
                .containsExactly("Brückentag", "Herbstferien");
    }

    @Test
    void failingSourceKeepsStoredHolidays() {
        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(
                entry("1", "Herbstferien", "2026-11-02", "2026-11-06")));
        holidays.refresh("BY", TODAY);

        when(client.fetch(eq("BY"), any(), any())).thenThrow(new HolidayException("nicht erreichbar", null));
        assertThatThrownBy(() -> holidays.refresh("BY", TODAY)).isInstanceOf(HolidayException.class);

        assertThat(holidays.list("BY", TODAY, TODAY.plusYears(1))).hasSize(1);
    }

    @Test
    void manualEntriesNeedTheSettingsRightAndValidInput() {
        User guest = users.create("gast", "passwort1", java.util.Set.of());
        assertThatThrownBy(() -> holidays.addManual("BY", "X", TODAY, TODAY, guest))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> holidays.addManual("XX", "X", TODAY, TODAY, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bundesland");
        assertThatThrownBy(() -> holidays.addManual("BY", "X", TODAY, TODAY.minusDays(1), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ende");

        SchoolHoliday manual = holidays.addManual("BY", "Frei", TODAY, TODAY, admin);
        holidays.deleteManual(manual.getId(), admin);
        assertThat(holidays.list("BY", TODAY, TODAY)).isEmpty();
    }

    @Test
    void onlineEntriesCannotBeDeletedByHand() {
        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(
                entry("1", "Herbstferien", "2026-11-02", "2026-11-06")));
        holidays.refresh("BY", TODAY);
        Long id = holidays.list("BY", TODAY, TODAY.plusYears(1)).getFirst().getId();

        assertThatThrownBy(() -> holidays.deleteManual(id, admin)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selbst eingetragene");
    }

    @Test
    void schoolDaysKnowHolidaysAndPublicHolidays() {
        holidays.addManual("BY", "Brückentag", LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 15), admin);

        assertThat(schoolDays.reason(LocalDate.of(2026, 5, 15), "BY")).contains("Brückentag");
        assertThat(schoolDays.reason(LocalDate.of(2026, 5, 1), "BY")).contains("Feiertag");
        assertThat(schoolDays.reason(LocalDate.of(2026, 5, 18), "BY")).isEmpty();
        assertThat(schoolDays.reason(LocalDate.of(2026, 5, 15), null)).isEmpty();
        assertThat(schoolDays.reason(LocalDate.of(2026, 5, 15), "NW")).isEmpty();
    }

    @Test
    void holidaysTakeTheSchoolDayOutOfTheBlockers() {
        SchoolProfile profile = school.createProfile(lena.getId(), "BY");
        SchoolYear year = school.startYear(profile.getId(), "2026/27", "Grundschule", "3a",
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 7, 31));
        SchoolSubject math = timetable.addSubject(year.getId(), "Mathe", null, null, admin);
        timetable.addLesson(year.getId(), math.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), null,
                WeekKind.EVERY, admin);
        assertThat(blockerOn(LocalDate.of(2026, 11, 2))).isPresent();

        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(
                entry("1", "Herbstferien", "2026-11-02", "2026-11-06")));
        holidays.refresh("BY", TODAY);

        assertThat(blockerOn(LocalDate.of(2026, 11, 2))).as("Ferien").isEmpty();
        assertThat(blockerOn(LocalDate.of(2026, 11, 9))).isPresent();

        Long manual = holidays.addManual("BY", "Brückentag", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9), admin)
                .getId();
        assertThat(blockerOn(LocalDate.of(2026, 11, 9))).isEmpty();
        holidays.deleteManual(manual, admin);
        assertThat(blockerOn(LocalDate.of(2026, 11, 9))).isPresent();
    }

    private Optional<Blocker> blockerOn(LocalDate day) {
        return blockers.findAllByMemberId(lena.getId()).stream()
                .filter(b -> b.getKind() == BlockerKind.SCHOOL && b.startsOn(day)).findFirst();
    }
}
