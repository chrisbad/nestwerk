package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.school.HolidayService;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class SchoolToolsTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired SchoolTools schoolTools;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired HolidayService holidays;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User kid;
    User stranger;
    FamilyMember lena;
    SchoolYear year;
    SchoolSubject math;

    @BeforeEach
    void setUp() {
        Long normal = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        admin = users.createAdministrator("chef", "passwort1");
        kid = users.create("lena", "passwort1", Set.of(normal));
        stranger = users.create("fremd", "passwort1", Set.of());
        lena = members.create("Lena", "#aabbcc", kid.getId());
        SchoolProfile profile = school.createProfile(lena.getId(), "BY");
        year = school.startYear(profile.getId(), "2026/27", "Grundschule", "3a", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", null, null, admin);
        timetable.addLesson(year.getId(), math.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(8, 45),
                "R12", WeekKind.EVERY, admin);
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void listsChildrenAndTheirActiveYear() {
        List<SchoolTools.ChildItem> children = schoolTools.listSchoolChildren(as(admin));

        assertThat(children).hasSize(1);
        assertThat(children.getFirst().name()).isEqualTo("Lena");
        assertThat(children.getFirst().yearId()).isEqualTo(year.getId());
        assertThat(children.getFirst().className()).isEqualTo("3a");
        assertThat(children.getFirst().state()).isEqualTo("BY");
    }

    @Test
    void timetableShowsLessonsWithIdsAndChanges() {
        schoolTools.markLesson(as(admin), timetable.lessons(year.getId()).getFirst().getId(), "2026-10-12",
                "cancelled", "krank");

        List<SchoolTools.DayItem> week = schoolTools.getTimetable(as(admin), year.getId(), "2026-10-14");

        assertThat(week).hasSize(5);
        SchoolTools.DayItem monday = week.getFirst();
        assertThat(monday.date()).isEqualTo(MONDAY);
        assertThat(monday.lessons()).hasSize(1);
        assertThat(monday.lessons().getFirst().subject()).isEqualTo("Mathe");
        assertThat(monday.lessons().getFirst().change()).isEqualTo("CANCELLED");
        assertThat(monday.lessons().getFirst().note()).isEqualTo("krank");
        assertThat(week.get(3).free()).isEqualTo(null);
    }

    @Test
    void homeworkRoundTrip() {
        SchoolTools.HomeworkItem added = schoolTools.addHomework(as(admin), year.getId(), "Seite 12", "2026-10-15",
                math.getId(), null, "weekly");

        assertThat(added.recurring()).isTrue();
        assertThat(schoolTools.listHomework(as(admin), year.getId(), null, null)).extracting(
                SchoolTools.HomeworkItem::title).containsExactly("Seite 12");

        schoolTools.setHomeworkDone(as(admin), added.todoId(), true);

        assertThat(schoolTools.listHomework(as(admin), year.getId(), null, false).getFirst().dueOn())
                .isEqualTo(LocalDate.of(2026, 10, 22));
        assertThatThrownBy(() -> schoolTools.addHomework(as(admin), year.getId(), "x", "2026-10-15", null, null,
                "hourly")).isInstanceOf(Tools.ToolException.class).hasMessageContaining("repeat");
    }

    @Test
    void examsAndHolidays() {
        SchoolTools.ExamItem exam = schoolTools.addSchoolExam(as(admin), year.getId(), "exam", "2026-10-20",
                math.getId(), "Brüche", 3);
        holidays.addManual("BY", "Brückentag", LocalDate.now().plusDays(3), LocalDate.now().plusDays(3), admin);

        assertThat(exam.subject()).isEqualTo("Mathe");
        assertThat(schoolTools.listSchoolExams(as(admin), year.getId())).extracting(SchoolTools.ExamItem::title)
                .containsExactly("Brüche");
        assertThat(schoolTools.listSchoolHolidays(as(admin), year.getId())).extracting(SchoolTools.HolidayItem::name)
                .containsExactly("Brückentag");
    }

    @Test
    void gradesFollowTheGradeRights() {
        SchoolTools.GradeItem grade = schoolTools.addSchoolGrade(as(admin), year.getId(), math.getId(), 1, "2+", null,
                "2026-10-01", 100, "Brüche");
        schoolTools.setSchoolReportGrade(as(admin), year.getId(), math.getId(), 1, "2");

        assertThat(grade.grade()).isEqualTo("2+");
        SchoolTools.GradesItem overview = schoolTools.listSchoolGrades(as(admin), year.getId(), 1);
        assertThat(overview.overallAverage()).isEqualTo("1,70");
        assertThat(overview.subjects().getFirst().reportGrade()).isEqualTo("2");

        assertThat(schoolTools.listSchoolGrades(as(kid), year.getId(), 1).overallAverage()).isEqualTo("1,70");
        assertThatThrownBy(() -> schoolTools.addSchoolGrade(as(kid), year.getId(), math.getId(), 1, "1", null, null,
                null, null)).isInstanceOf(Tools.ToolException.class).hasMessageContaining("Berechtigung");
        assertThatThrownBy(() -> schoolTools.listSchoolGrades(as(stranger), year.getId(), 1))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void lettersRoundTrip() {
        SchoolTools.LetterItem letter = schoolTools.addSchoolLetter(as(admin), year.getId(), "Zoo", "2026-10-20", null);
        schoolTools.setSchoolLetterSigned(as(admin), letter.letterId(), true);

        assertThat(schoolTools.listSchoolLetters(as(admin), year.getId())).extracting(SchoolTools.LetterItem::signed)
                .containsExactly(true);
    }

    @Test
    void strangersAreRefused() {
        assertThatThrownBy(() -> schoolTools.listSchoolChildren(as(stranger))).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("Berechtigung");
        assertThatThrownBy(() -> schoolTools.addHomework(as(stranger), year.getId(), "x", "2026-10-15", null, null,
                null)).isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> schoolTools.getTimetable(as(admin), 999_999L, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("nicht gefunden");
    }

    @Test
    void todayListsTheChildDay() {
        schoolTools.addHomework(as(admin), year.getId(), "Seite 12", "2026-10-12", null, null, null);

        List<SchoolTools.TodayItem> today = schoolTools.schoolToday(as(admin), "2026-10-12");

        assertThat(today).hasSize(1);
        assertThat(today.getFirst().name()).isEqualTo("Lena");
        assertThat(today.getFirst().schoolHours()).isEqualTo("08:00–08:45");
        assertThat(today.getFirst().homeworkDue()).containsExactly("Seite 12 (2026-10-12)");
    }
}
