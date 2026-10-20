package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerBands;
import club.badernet.nestwerk.member.FamilyMember;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class SchoolCalendarBandsTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

    @Autowired BlockerBands bands;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired HolidayService holidays;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;

    User admin;
    FamilyMember lena;
    SchoolYear year;
    SchoolSubject math;
    SchoolSubject german;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), "BY");
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", null, admin);
        german = timetable.addSubject(year.getId(), "Deutsch", null, admin);
    }

    private Lesson lesson(SchoolSubject subject, int hour) {
        return timetable.addLesson(year.getId(), LessonInput.timed(subject.getId(), DayOfWeek.MONDAY,
                LocalTime.of(hour, 0), LocalTime.of(hour, 45), null, WeekKind.EVERY), admin);
    }

    private List<String> labelsOn(LocalDate day, User viewer) {
        return bands.between(day, day, viewer).getOrDefault(day, List.of()).stream()
                .filter(b -> b.kind().equals(SchoolCalendarBands.HOLIDAY)
                        || b.kind().equals(SchoolCalendarBands.CANCELLED))
                .map(BlockerBands.Band::label).toList();
    }

    @Test
    void holidaysAppearOnEveryDayForChildrenOfTheState() {
        holidays.addManual("BY", "Herbstferien", MONDAY, MONDAY.plusDays(4), admin);

        Map<LocalDate, List<BlockerBands.Band>> week = bands.between(MONDAY, MONDAY.plusDays(6), admin);

        assertThat(week.get(MONDAY.plusDays(2))).extracting(BlockerBands.Band::label)
                .contains("Lena · Herbstferien");
        assertThat(week.get(MONDAY.plusDays(2))).extracting(BlockerBands.Band::kind).contains("holiday");
        assertThat(week.getOrDefault(MONDAY.plusDays(5), List.of())).isEmpty();
    }

    @Test
    void trailingCancellationsReadAsFreeFrom() {
        lesson(math, 8);
        lesson(german, 9);
        Lesson last = lesson(math, 10);
        timetable.markLesson(last.getId(), MONDAY, ChangeKind.CANCELLED, null, admin);

        assertThat(labelsOn(MONDAY, admin)).containsExactly("Lena · frei ab 09:45");
    }

    @Test
    void singleCancellationsNameTheSubjectAndWholeDaysAreSummarised() {
        lesson(math, 8);
        Lesson middle = lesson(german, 9);
        lesson(math, 10);
        timetable.markLesson(middle.getId(), MONDAY, ChangeKind.CANCELLED, null, admin);
        assertThat(labelsOn(MONDAY, admin)).containsExactly("Lena · Deutsch entfällt");

        timetable.markDay(year.getId(), MONDAY.plusWeeks(1), LessonSelection.wholeDay(), ChangeKind.CANCELLED,
                "Studientag", admin);
        assertThat(labelsOn(MONDAY.plusWeeks(1), admin)).containsExactly("Lena · Unterricht entfällt");
    }

    @Test
    void substitutionsStayOutOfTheCalendar() {
        Lesson first = lesson(math, 8);
        timetable.markLesson(first.getId(), MONDAY, ChangeKind.SUBSTITUTE, "Herr Kaya", admin);

        assertThat(labelsOn(MONDAY, admin)).isEmpty();
    }

    @Test
    void viewersWithoutSchoolAccessSeeNoSchoolBands() {
        Lesson first = lesson(math, 8);
        lesson(german, 9);
        timetable.markLesson(first.getId(), MONDAY, ChangeKind.CANCELLED, null, admin);
        User outsider = users.create("fremd", "passwort1", Set.of());

        assertThat(labelsOn(MONDAY, outsider)).isEmpty();
        assertThat(labelsOn(MONDAY, admin)).containsExactly("Lena · Beginn erst 09:00");
    }

    @Test
    void lessonsInPeriodsShowTheirNumber() {
        FamilyMember member = new FamilyMember("Tom", "#123456", null);
        Lesson lesson = new Lesson(1L, 1L, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(8, 45), null,
                WeekKind.EVERY);
        lesson.placeInPeriods(2, 2, LocalTime.of(8, 50), LocalTime.of(9, 35));
        Lesson before = new Lesson(1L, 1L, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(8, 45), null,
                WeekKind.EVERY);
        Lesson after = new Lesson(1L, 1L, DayOfWeek.MONDAY, LocalTime.of(9, 40), LocalTime.of(10, 25), null,
                WeekKind.EVERY);
        SchoolSubject subject = new SchoolSubject(1L, "Kunst", "#000000");

        List<BlockerBands.Band> result = SchoolCalendarBands.cancellationBands(member, List.of(
                new Timetable.Entry(before, subject, null),
                new Timetable.Entry(lesson, subject, new LessonChange(null, MONDAY, ChangeKind.CANCELLED, null)),
                new Timetable.Entry(after, subject, null)));

        assertThat(result).extracting(BlockerBands.Band::label).containsExactly("Tom · Kunst entfällt (2.)");
        assertThat(result).extracting(BlockerBands.Band::times).containsExactly("08:50–09:35");
    }
}
