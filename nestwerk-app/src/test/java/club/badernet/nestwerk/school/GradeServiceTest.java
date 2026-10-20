package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
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

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class GradeServiceTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired GradeService grades;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User kid;
    User sibling;
    User parent;
    FamilyMember lena;
    SchoolYear year;
    SchoolSubject math;
    SchoolSubject german;

    @BeforeEach
    void setUp() {
        Long normal = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        admin = users.createAdministrator("chef", "passwort1");
        kid = users.create("lena", "passwort1", Set.of(normal));
        sibling = users.create("tom", "passwort1", Set.of(normal));
        parent = users.create("mama", "passwort1", Set.of());
        lena = members.create("Lena", "#aabbcc", kid.getId());
        members.create("Tom", "#ccbbaa", sibling.getId());
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", null, admin);
        german = timetable.addSubject(year.getId(), "Deutsch", null, admin);
    }

    @Test
    void overviewShowsGradesAverageReportAndOverall() {
        grades.add(year.getId(), math.getId(), 1, "2+", 100, GradeKind.WRITTEN, DAY, "Bruchrechnen", admin);
        grades.add(year.getId(), math.getId(), 1, "3", 200, GradeKind.ORAL, DAY.plusDays(7), null, admin);
        grades.add(year.getId(), german.getId(), 1, "1-", 100, GradeKind.WRITTEN, DAY, null, admin);
        grades.add(year.getId(), german.getId(), 2, "5", 100, GradeKind.WRITTEN, DAY, null, admin);
        grades.setReportGrade(year.getId(), math.getId(), 1, "2", admin);

        GradeService.Overview first = grades.overview(year.getId(), 1, admin);

        GradeService.SubjectGrades mathRow = first.subjects().stream()
                .filter(s -> s.subject().getId().equals(math.getId())).findFirst().orElseThrow();
        assertThat(mathRow.grades()).extracting(SchoolGrade::getLabel).containsExactly("2+", "3");
        assertThat(mathRow.average()).isEqualTo(2.57);
        assertThat(mathRow.averageLabel()).isEqualTo("2,57");
        assertThat(mathRow.report().getLabel()).isEqualTo("2");
        assertThat(mathRow.trend()).containsExactly(1.7, 2.57);
        assertThat(first.overallAverage()).as("aus den ungerundeten Fachschnitten").isEqualTo(1.93);

        assertThat(grades.overview(year.getId(), 2, admin).subjects().stream()
                .flatMap(s -> s.grades().stream())).extracting(SchoolGrade::getLabel).containsExactly("5");
    }

    @Test
    void updateAndDeleteChangeTheAverage() {
        SchoolGrade g = grades.add(year.getId(), math.getId(), 1, "4", 100, GradeKind.WRITTEN, DAY, null, admin);

        grades.update(g.getId(), math.getId(), 1, "2-", 100, GradeKind.ORAL, DAY, "besser", admin);
        assertThat(grades.overview(year.getId(), 1, admin).subjects().stream()
                .filter(s -> s.subject().getId().equals(math.getId())).findFirst().orElseThrow().grades().getFirst()
                .getNote()).isEqualTo("besser");

        grades.delete(g.getId(), admin);
        assertThat(grades.overview(year.getId(), 1, admin).overallAverage()).isNull();
    }

    @Test
    void reportGradeCanBeChangedAndRemoved() {
        grades.setReportGrade(year.getId(), math.getId(), 1, "3+", admin);
        grades.setReportGrade(year.getId(), math.getId(), 1, "2-", admin);
        assertThat(grades.overview(year.getId(), 1, admin).subjects().stream()
                .filter(s -> s.subject().getId().equals(math.getId())).findFirst().orElseThrow().report().getLabel())
                .isEqualTo("2-");

        grades.setReportGrade(year.getId(), math.getId(), 1, " ", admin);
        assertThat(grades.overview(year.getId(), 1, admin).subjects().stream()
                .filter(s -> s.subject().getId().equals(math.getId())).findFirst().orElseThrow().report()).isNull();
    }

    @Test
    void childSeesOnlyItsOwnGradesAndCannotEdit() {
        grades.add(year.getId(), math.getId(), 1, "2", 100, GradeKind.WRITTEN, DAY, null, admin);

        assertThat(grades.mayView(year.getId(), kid)).isTrue();
        assertThat(grades.overview(year.getId(), 1, kid).overallAverage()).isEqualTo(2.0);
        assertThatThrownBy(() -> grades.add(year.getId(), math.getId(), 1, "1", 100, GradeKind.WRITTEN, DAY, null, kid))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(grades.mayView(year.getId(), sibling)).as("Geschwister").isFalse();
        assertThatThrownBy(() -> grades.overview(year.getId(), 1, sibling)).isInstanceOf(AccessDeniedException.class);
        assertThat(grades.mayView(year.getId(), parent)).as("ohne Recht").isFalse();
        assertThat(grades.mayView(year.getId(), admin)).isTrue();
    }

    @Test
    void validatesInput() {
        assertThatThrownBy(() -> grades.add(year.getId(), math.getId(), 3, "2", 100, GradeKind.WRITTEN, DAY, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Halbjahr");
        assertThatThrownBy(() -> grades.add(year.getId(), math.getId(), 1, "7", 100, GradeKind.WRITTEN, DAY, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Note");
        assertThatThrownBy(() -> grades.add(year.getId(), math.getId(), 1, "2", 10, GradeKind.WRITTEN, DAY, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Gewicht");
        assertThatThrownBy(() -> grades.add(year.getId(), 999_999L, 1, "2", 100, GradeKind.WRITTEN, DAY, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fach");
        assertThatThrownBy(() -> grades.add(year.getId(), math.getId(), 1, "2", 100, null, DAY, null, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grades.add(year.getId(), math.getId(), 1, "2", 100, GradeKind.WRITTEN, null, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Datum");
    }

    @Test
    void subjectsOfOtherYearsAreRejected() {
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();
        SchoolYear next = school.startYear(profile.getId(), "2027/28", null, "4a", LocalDate.of(2027, 9, 1),
                LocalDate.of(2028, 7, 31));

        assertThatThrownBy(() -> grades.add(next.getId(), math.getId(), 1, "2", 100, GradeKind.WRITTEN, DAY, null,
                admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fach");
    }
}
