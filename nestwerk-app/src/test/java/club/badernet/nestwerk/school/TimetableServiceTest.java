package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerRepository;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TimetableServiceTest {

    static final LocalDate MON_1 = LocalDate.of(2026, 8, 3);
    static final LocalDate MON_2 = MON_1.plusWeeks(1);
    static final LocalDate MON_3 = MON_1.plusWeeks(2);
    static final LocalDate MON_4 = MON_1.plusWeeks(3);

    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;
    @Autowired BlockerRepository blockers;

    User admin;
    User parent;
    FamilyMember lena;
    SchoolYear year;
    SchoolSubject math;
    SchoolSubject german;
    SchoolSubject english;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        parent = users.create("mama", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", null, "3a", MON_1, LocalDate.of(2026, 8, 30));
        math = timetable.addSubject(year.getId(), "Mathe", null, admin);
        german = timetable.addSubject(year.getId(), "Deutsch", "#e07a3f", admin);
        english = timetable.addSubject(year.getId(), "Englisch", null, admin);
    }

    private Lesson lesson(SchoolSubject subject, int from, int to, WeekKind kind) {
        return timetable.addLesson(year.getId(), LessonInput.timed(subject.getId(), DayOfWeek.MONDAY,
                LocalTime.of(from / 60, from % 60), LocalTime.of(to / 60, to % 60), "R1", kind), admin);
    }

    private Optional<Blocker> blockerOn(LocalDate day) {
        return blockers.findAllByMemberId(lena.getId()).stream()
                .filter(b -> b.getKind() == BlockerKind.SCHOOL && b.startsOn(day)).findFirst();
    }

    private void abPlan() {
        timetable.setWeekPlan(year.getId(), MON_1, admin);
        lesson(math, 480, 525, WeekKind.EVERY);      // 08:00–08:45
        lesson(german, 525, 570, WeekKind.A);        // 08:45–09:30
        lesson(english, 570, 615, WeekKind.B);       // 09:30–10:15
    }

    @Test
    void weekKindAlternatesFromTheFirstAWeek() {
        assertThat(Timetable.weekKind(MON_1, MON_1)).isEqualTo(WeekKind.A);
        assertThat(Timetable.weekKind(MON_1.plusDays(4), MON_1)).isEqualTo(WeekKind.A);
        assertThat(Timetable.weekKind(MON_2, MON_1)).isEqualTo(WeekKind.B);
        assertThat(Timetable.weekKind(MON_3, MON_1)).isEqualTo(WeekKind.A);
        assertThat(Timetable.weekKind(MON_1.minusWeeks(1), MON_1)).isEqualTo(WeekKind.B);
        assertThat(Timetable.weekKind(MON_1, null)).isNull();
    }

    @Test
    void daySelectsLessonsByWeekKind() {
        abPlan();

        assertThat(timetable.day(year.getId(), MON_1)).extracting(e -> e.subject().getName())
                .containsExactly("Mathe", "Deutsch");
        assertThat(timetable.day(year.getId(), MON_2)).extracting(e -> e.subject().getName())
                .containsExactly("Mathe", "Englisch");
        assertThat(timetable.day(year.getId(), MON_1.plusDays(1))).isEmpty();
        assertThat(timetable.day(year.getId(), LocalDate.of(2026, 9, 7))).isEmpty();
    }

    @Test
    void subjectsGetPaletteColorAndRejectDuplicates() {
        assertThat(math.getColor()).matches("#[0-9a-f]{6}");
        assertThat(german.getColor()).isEqualTo("#e07a3f");
        assertThatThrownBy(() -> timetable.addSubject(year.getId(), "mathe", null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gibt es schon");
        assertThatThrownBy(() -> timetable.addSubject(year.getId(), "Kunst", "rot", admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Farbe");
    }

    @Test
    void rejectsOverlapsAndAbLessonsWithoutWeekPlan() {
        lesson(math, 480, 525, WeekKind.EVERY);

        assertThatThrownBy(() -> lesson(german, 500, 560, WeekKind.EVERY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("überschneidet");
        assertThatThrownBy(() -> lesson(german, 525, 570, WeekKind.A))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A-Woche");

        timetable.setWeekPlan(year.getId(), MON_1, admin);
        lesson(german, 525, 570, WeekKind.A);
        lesson(english, 525, 570, WeekKind.B);
        assertThatThrownBy(() -> lesson(english, 540, 580, WeekKind.A))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("überschneidet");
        assertThatThrownBy(() -> timetable.setWeekPlan(year.getId(), null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A- oder B-Wochen");
    }

    @Test
    void onlyEditorsMayChange() {
        assertThatThrownBy(() -> timetable.addSubject(year.getId(), "Kunst", null, parent))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> timetable.addLesson(year.getId(), LessonInput.timed(math.getId(), DayOfWeek.MONDAY,
                LocalTime.of(8, 0), LocalTime.of(9, 0), null, WeekKind.EVERY), parent))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createsSchoolBlockersPerWeekKind() {
        abPlan();

        Blocker a = blockerOn(MON_1).orElseThrow();
        assertThat(a.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(a.getEndTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(a.getMemberIds()).containsExactly(lena.getId());
        Blocker b = blockerOn(MON_2).orElseThrow();
        assertThat(b.getEndTime()).isEqualTo(LocalTime.of(10, 15));
        assertThat(blockerOn(MON_3).orElseThrow().getId()).isEqualTo(a.getId());
        assertThat(blockerOn(MON_4).orElseThrow().getId()).isEqualTo(b.getId());
        assertThat(blockerOn(MON_1.plusDays(1))).isEmpty();
    }

    @Test
    void cancelledLessonShrinksTheSchoolDayAndCanBeRestored() {
        abPlan();
        Lesson mathLesson = timetable.lessons(year.getId()).stream()
                .filter(l -> l.getSubjectId().equals(math.getId())).findFirst().orElseThrow();

        timetable.markLesson(mathLesson.getId(), MON_1, ChangeKind.CANCELLED, "krank", admin);

        Blocker shrunk = blockerOn(MON_1).orElseThrow();
        assertThat(shrunk.getStartTime()).isEqualTo(LocalTime.of(8, 45));
        assertThat(blockerOn(MON_3).orElseThrow().getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(timetable.day(year.getId(), MON_1).getFirst().cancelled()).isTrue();

        timetable.clearChange(timetable.change(mathLesson.getId(), MON_1).orElseThrow().getId(), admin);
        assertThat(blockerOn(MON_1).orElseThrow().getStartTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void substituteKeepsTheTimeAndEverythingCancelledFreesTheDay() {
        abPlan();
        List<Lesson> monday = timetable.lessons(year.getId());
        Lesson germanLesson = monday.stream().filter(l -> l.getSubjectId().equals(german.getId())).findFirst()
                .orElseThrow();
        Lesson mathLesson = monday.stream().filter(l -> l.getSubjectId().equals(math.getId())).findFirst()
                .orElseThrow();

        timetable.markLesson(germanLesson.getId(), MON_1, ChangeKind.SUBSTITUTE, "Vertretung Herr Kunz", admin);
        assertThat(blockerOn(MON_1).orElseThrow().getEndTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(timetable.day(year.getId(), MON_1).get(1).substituted()).isTrue();

        timetable.markLesson(germanLesson.getId(), MON_1, ChangeKind.CANCELLED, null, admin);
        timetable.markLesson(mathLesson.getId(), MON_1, ChangeKind.CANCELLED, null, admin);
        assertThat(blockerOn(MON_1)).isEmpty();
    }

    @Test
    void rejectsChangeOnTheWrongWeekday() {
        Lesson l = lesson(math, 480, 525, WeekKind.EVERY);
        assertThatThrownBy(() -> timetable.markLesson(l.getId(), MON_1.plusDays(1), ChangeKind.CANCELLED, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Wochentag");
    }

    @Test
    void publicHolidaysAreFreeAndDeletingLessonsRemovesBlockers() {
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();
        school.changeState(profile.getId(), "BY");
        SchoolYear holidayYear = school.startYear(profile.getId(), "2026/27 b", null, "3a",
                LocalDate.of(2026, 12, 21), LocalDate.of(2027, 1, 18));
        SchoolSubject subject = timetable.addSubject(holidayYear.getId(), "Mathe", null, admin);
        timetable.addLesson(holidayYear.getId(), LessonInput.timed(subject.getId(), DayOfWeek.WEDNESDAY,
                LocalTime.of(8, 0), LocalTime.of(9, 0), null, WeekKind.EVERY), admin);

        assertThat(blockerOn(LocalDate.of(2026, 12, 23))).isPresent();
        assertThat(blockerOn(LocalDate.of(2027, 1, 6))).as("Heilige Drei Könige in Bayern").isEmpty();
        assertThat(blockerOn(LocalDate.of(2027, 1, 13))).isPresent();

        Lesson only = timetable.lessons(holidayYear.getId()).getFirst();
        timetable.deleteLesson(only.getId(), admin);
        assertThat(blockers.findAllByMemberId(lena.getId())).noneMatch(b -> b.getKind() == BlockerKind.SCHOOL);
    }

    @Test
    void switchingTheActiveYearMovesTheBlockers() {
        abPlan();
        assertThat(blockerOn(MON_1)).isPresent();
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();

        school.startYear(profile.getId(), "2027/28", null, "4a", LocalDate.of(2027, 9, 1),
                LocalDate.of(2028, 7, 31));
        assertThat(blockerOn(MON_1)).isEmpty();

        school.activate(year.getId());
        assertThat(blockerOn(MON_1)).isPresent();
    }

    @Test
    void subjectsHaveSeveralTeacherContacts() {
        var weber = timetable.addTeacher(math.getId(), "Frau Weber", "weber@schule.de", admin);
        var kaya = timetable.addTeacher(math.getId(), "Herr Kaya", null, admin);

        assertThat(timetable.teachers(year.getId()).get(math.getId())).extracting(c -> c.getName())
                .containsExactly("Frau Weber", "Herr Kaya");
        assertThat(weber.getCategory()).isEqualTo(SchoolContacts.TEACHER);

        timetable.setTeachers(math.getId(), Set.of(kaya.getId()), admin);
        assertThat(timetable.teachers(year.getId()).get(math.getId())).extracting(c -> c.getId())
                .containsExactly(kaya.getId());
        assertThatThrownBy(() -> timetable.setTeachers(math.getId(), Set.of(999_999L), admin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markDayCancelsBySubjectAndClearDayResets() {
        lesson(math, 480, 525, WeekKind.EVERY);
        lesson(german, 525, 570, WeekKind.EVERY);
        lesson(math, 570, 615, WeekKind.EVERY);

        List<Timetable.Entry> marked = timetable.markDay(year.getId(), MON_1, new LessonSelection("mathe", null, null),
                ChangeKind.CANCELLED, null, admin);

        assertThat(marked).hasSize(2);
        assertThat(timetable.day(year.getId(), MON_1)).extracting(Timetable.Entry::cancelled)
                .containsExactly(true, false, true);
        assertThat(timetable.clearDay(year.getId(), MON_1, LessonSelection.wholeDay(), admin)).isEqualTo(2);
        assertThat(timetable.day(year.getId(), MON_1)).noneMatch(Timetable.Entry::cancelled);
        assertThatThrownBy(() -> timetable.markDay(year.getId(), MON_1, new LessonSelection("Kunst", null, null),
                ChangeKind.CANCELLED, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keine passende Stunde");
    }

    @Test
    void roomChangeReplacesTheRoomForThatDay() {
        Lesson l = lesson(math, 480, 525, WeekKind.EVERY);

        timetable.markLesson(l.getId(), MON_1, ChangeKind.ROOM, "Aula", admin);

        assertThat(timetable.day(year.getId(), MON_1).getFirst().room()).isEqualTo("Aula");
        assertThat(timetable.day(year.getId(), MON_2).getFirst().room()).isEqualTo("R1");
    }

    @Test
    void changeLessonMovesItAndChecksOverlaps() {
        Lesson first = lesson(math, 480, 525, WeekKind.EVERY);
        lesson(german, 525, 570, WeekKind.EVERY);

        timetable.changeLesson(first.getId(), LessonInput.timed(english.getId(), DayOfWeek.MONDAY, LocalTime.of(7, 0),
                LocalTime.of(7, 45), "R9", WeekKind.EVERY), admin);
        assertThat(timetable.lessons(year.getId()).getFirst().getSubjectId()).isEqualTo(english.getId());

        assertThatThrownBy(() -> timetable.changeLesson(first.getId(), LessonInput.timed(english.getId(),
                DayOfWeek.MONDAY, LocalTime.of(8, 50), LocalTime.of(9, 10), null, WeekKind.EVERY), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("überschneidet");
    }
}
