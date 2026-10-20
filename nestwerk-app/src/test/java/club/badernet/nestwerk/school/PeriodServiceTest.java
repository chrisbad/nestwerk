package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class PeriodServiceTest {

    @Autowired PeriodService periods;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired SchoolContacts schoolContacts;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;

    User admin;
    Contact park;
    SchoolYear year;
    SchoolSubject math;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        park = schoolContacts.createSchool(new SchoolContacts.SchoolInput("Schule am Park", null, null, null, null,
                null), admin);
        SchoolProfile profile = school.createProfile(members.create("Lena", "#aabbcc", null).getId(), null);
        year = school.startYear(profile.getId(), "2026/27", park.getId(), "3a", LocalDate.of(2026, 8, 3),
                LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", null, admin);
    }

    private static PeriodService.PeriodInput lesson(String from, String to) {
        return new PeriodService.PeriodInput(PeriodKind.LESSON, LocalTime.parse(from), LocalTime.parse(to), null);
    }

    private static PeriodService.PeriodInput pause(String from, String to) {
        return new PeriodService.PeriodInput(PeriodKind.BREAK, LocalTime.parse(from), LocalTime.parse(to), null);
    }

    @Test
    void numbersLessonsInOrderAndKeepsBreaksUnnumbered() {
        periods.saveGrid(park.getId(), List.of(lesson("08:50", "09:35"), pause("08:45", "08:50"),
                lesson("08:00", "08:45")), admin);

        assertThat(periods.grid(park.getId())).extracting(SchoolPeriod::displayName)
                .containsExactly("1. Stunde", "Pause", "2. Stunde");
        assertThat(periods.gridOfYear(year.getId())).hasSize(3);
    }

    @Test
    void rejectsOverlappingRowsAndRowsEndingBeforeTheyStart() {
        assertThatThrownBy(() -> periods.saveGrid(park.getId(), List.of(lesson("08:00", "08:45"),
                lesson("08:30", "09:15")), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("überschneiden");
        assertThatThrownBy(() -> periods.saveGrid(park.getId(), List.of(lesson("09:00", "08:45")), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nach ihrem Beginn");
    }

    @Test
    void lessonsInTheGridFollowChangedTimes() {
        periods.saveGrid(park.getId(), List.of(lesson("08:00", "08:45"), lesson("08:45", "09:30")), admin);
        Lesson doubleLesson = timetable.addLesson(year.getId(),
                LessonInput.inPeriods(math.getId(), DayOfWeek.MONDAY, 1, 2, null, WeekKind.EVERY), admin);
        assertThat(doubleLesson.getEndsAt()).isEqualTo(LocalTime.of(9, 30));

        periods.saveGrid(park.getId(), List.of(lesson("07:55", "08:40"), lesson("08:40", "09:25")), admin);

        Lesson moved = timetable.lessons(year.getId()).getFirst();
        assertThat(moved.getStartsAt()).isEqualTo(LocalTime.of(7, 55));
        assertThat(moved.getEndsAt()).isEqualTo(LocalTime.of(9, 25));
    }

    @Test
    void lessonsWithFreeTimesAreMatchedAndMissingPeriodsBecomeFreeTimes() {
        timetable.addLesson(year.getId(), LessonInput.timed(math.getId(), DayOfWeek.TUESDAY, LocalTime.of(8, 0),
                LocalTime.of(8, 45), null, WeekKind.EVERY), admin);
        timetable.addLesson(year.getId(), LessonInput.timed(math.getId(), DayOfWeek.TUESDAY, LocalTime.of(14, 0),
                LocalTime.of(15, 30), null, WeekKind.EVERY), admin);

        periods.saveGrid(park.getId(), List.of(lesson("08:00", "08:45")), admin);
        assertThat(timetable.lessons(year.getId())).extracting(Lesson::getPeriodFrom).containsExactly(1, null);

        periods.saveGrid(park.getId(), List.of(), admin);
        assertThat(timetable.lessons(year.getId())).allMatch(l -> !l.inPeriods());
        assertThat(timetable.lessons(year.getId()).getFirst().getStartsAt()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void lessonsNeedExistingPeriods() {
        periods.saveGrid(park.getId(), List.of(lesson("08:00", "08:45")), admin);

        assertThatThrownBy(() -> timetable.addLesson(year.getId(),
                LessonInput.inPeriods(math.getId(), DayOfWeek.MONDAY, 1, 3, null, WeekKind.EVERY), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Raster");
    }

    @Test
    void suggestionHasSixLessonsAndTwoBreaks() {
        List<PeriodService.PeriodInput> rows = PeriodService.suggestion();

        assertThat(rows).filteredOn(r -> r.kind() == PeriodKind.LESSON).hasSize(6);
        assertThat(rows).filteredOn(r -> r.kind() == PeriodKind.BREAK).hasSize(2);
    }
}
