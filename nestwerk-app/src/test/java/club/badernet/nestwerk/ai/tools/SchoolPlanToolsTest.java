package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.school.PeriodKind;
import club.badernet.nestwerk.school.PeriodService;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.Timetable;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

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
class SchoolPlanToolsTest {

    static final String MONDAY = "2026-10-12";

    @Autowired SchoolPlanTools planTools;
    @Autowired SchoolTools schoolTools;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User viewer;
    FamilyMember lena;
    SchoolYear year;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        viewer = users.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), "BY");
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 7, 31));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    /** School with grid, Mathe in periods 1–2 and Deutsch in period 3 on Mondays. */
    private void plan() {
        planTools.setSchoolOfYear(as(admin), year.getId(), null, "Schule am Park", "0911 123", "info@park.de");
        planTools.setSchoolPeriodGrid(as(admin), year.getId(),
                "08:00-08:45; 08:45-09:30; P 09:30-09:50 Hofpause\n09:50-10:35");
        Long math = planTools.addSchoolSubject(as(admin), year.getId(), "Mathe", null).subjectId();
        Long german = planTools.addSchoolSubject(as(admin), year.getId(), "Deutsch", "#e07a3f").subjectId();
        planTools.addSchoolLesson(as(admin), year.getId(), math, "monday", 1, 2, null, null, "R12", null);
        planTools.addSchoolLesson(as(admin), year.getId(), german, "MONDAY", 3, null, null, null, null, null);
    }

    @Test
    void buildsSchoolGridSubjectsAndLessons() {
        plan();

        assertThat(planTools.getSchoolPeriodGrid(as(viewer), year.getId()))
                .extracting(SchoolPlanTools.PeriodItem::kind, SchoolPlanTools.PeriodItem::number)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LESSON", 1),
                        org.assertj.core.groups.Tuple.tuple("LESSON", 2),
                        org.assertj.core.groups.Tuple.tuple("BREAK", null),
                        org.assertj.core.groups.Tuple.tuple("LESSON", 3));
        assertThat(planTools.listSchoolLessons(as(viewer), year.getId()))
                .extracting(SchoolPlanTools.LessonItem::subject, SchoolPlanTools.LessonItem::start,
                        SchoolPlanTools.LessonItem::end)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Mathe", "08:00", "09:30"),
                        org.assertj.core.groups.Tuple.tuple("Deutsch", "09:50", "10:35"));
        assertThat(schoolTools.listSchoolChildren(as(viewer)).getFirst().school()).isEqualTo("Schule am Park");
    }

    @Test
    void teachersAreContactsWithEmail() {
        plan();
        Long math = planTools.listSchoolTeachers(as(admin), year.getId()).subjects().stream()
                .filter(s -> s.name().equals("Mathe")).findFirst().orElseThrow().subjectId();

        SchoolPlanTools.ContactItem weber = planTools.addSubjectTeacher(as(admin), math, "Frau Weber",
                "weber@schule.de");
        SchoolPlanTools.TeachersItem teachers = planTools.listSchoolTeachers(as(viewer), year.getId());

        assertThat(teachers.school().email()).isEqualTo("info@park.de");
        assertThat(teachers.subjects()).filteredOn(s -> s.name().equals("Mathe")).singleElement()
                .satisfies(s -> assertThat(s.teachers()).extracting(SchoolPlanTools.ContactItem::email)
                        .containsExactly("weber@schule.de"));

        planTools.setSubjectTeachers(as(admin), math, List.of());
        assertThat(timetable.teachers(year.getId()).get(math)).isEmpty();
        assertThat(weber.contactId()).isNotNull();
    }

    @Test
    void reportsCancellationsBySubjectOrPeriodAndClearsThem() {
        plan();

        SchoolPlanTools.ChangeItem bySubject = planTools.reportLessonChange(as(admin), year.getId(), MONDAY,
                "CANCELLED", "deutsch", null, null, "krank");
        assertThat(bySubject.subjects()).containsExactly("Deutsch");

        SchoolPlanTools.ChangeItem byPeriod = planTools.reportLessonChange(as(admin), year.getId(), MONDAY, "ROOM",
                null, 2, 2, "Aula");
        assertThat(byPeriod.subjects()).containsExactly("Mathe");
        assertThat(timetable.day(year.getId(), LocalDate.parse(MONDAY))).extracting(Timetable.Entry::room)
                .containsExactly("Aula", null);

        assertThat(planTools.clearLessonChange(as(admin), year.getId(), MONDAY, null, null, null))
                .startsWith("2 ");
        assertThatThrownBy(() -> planTools.reportLessonChange(as(viewer), year.getId(), MONDAY, "CANCELLED", null,
                null, null, null)).isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void updatesAndDeletesLessonsAndSubjects() {
        plan();
        SchoolPlanTools.LessonItem first = planTools.listSchoolLessons(as(admin), year.getId()).getFirst();
        Long mathId = timetable.subjects(year.getId()).stream().filter(s -> s.getName().equals("Mathe"))
                .findFirst().orElseThrow().getId();

        planTools.updateSchoolLesson(as(admin), first.lessonId(), mathId, "TUESDAY", null, null, "14:00", "15:30",
                "AG-Raum", "EVERY");
        assertThat(timetable.lessons(year.getId())).anyMatch(l -> l.getStartsAt().equals(LocalTime.of(14, 0))
                && !l.inPeriods());

        planTools.updateSchoolSubject(as(admin), mathId, "Mathematik", null);
        planTools.deleteSchoolLesson(as(admin), first.lessonId());
        planTools.deleteSchoolSubject(as(admin), mathId);
        assertThat(timetable.subjects(year.getId())).extracting(s -> s.getName()).containsExactly("Deutsch");
    }

    @Test
    void gridParserUnderstandsBreaksAndLabels() {
        List<PeriodService.PeriodInput> rows = SchoolPlanTools.parseGrid("8:00-8:45 ; Pause 08:45-09:05 Frühstück");

        assertThat(rows).extracting(PeriodService.PeriodInput::kind)
                .containsExactly(PeriodKind.LESSON, PeriodKind.BREAK);
        assertThat(rows.get(1).label()).isEqualTo("Frühstück");
        assertThatThrownBy(() -> SchoolPlanTools.parseGrid("erste Stunde"))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void gridNeedsASchool() {
        assertThatThrownBy(() -> planTools.setSchoolPeriodGrid(as(admin), year.getId(), "08:00-08:45"))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Schule");
    }
}
