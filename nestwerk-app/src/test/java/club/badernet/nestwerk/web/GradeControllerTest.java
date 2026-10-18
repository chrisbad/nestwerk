package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.school.GradeKind;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class GradeControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService users;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired GradeService grades;

    User admin;
    SchoolYear year;
    SchoolSubject math;

    @BeforeEach
    void setUp() {
        Long normal = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        admin = users.createAdministrator("chef", "passwort1");
        User kid = users.create("lena", "passwort1", Set.of(normal));
        users.create("tom", "passwort1", Set.of(normal));
        FamilyMember lena = members.create("Lena", "#aabbcc", kid.getId());
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", "Grundschule", "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", "#4d74ff", null, admin);
        grades.add(year.getId(), math.getId(), 1, "2+", 100, GradeKind.WRITTEN, LocalDate.of(2026, 10, 1), "Brüche",
                admin);
        grades.add(year.getId(), math.getId(), 1, "3", 100, GradeKind.ORAL, LocalDate.of(2026, 10, 8), null, admin);
    }

    private static RequestPostProcessor allGrades() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_GRADES"));
    }

    private static RequestPostProcessor ownGrades(String name) {
        return user(name).authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_GRADES_OWN"));
    }

    @Test
    void parentSeesGradesAverageAndTheForm() throws Exception {
        mockMvc.perform(get("/school/years/{id}/grades", year.getId()).param("term", "1").with(allGrades()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Noten Lena")))
                .andExpect(content().string(containsString("2+")))
                .andExpect(content().string(containsString("Brüche")))
                .andExpect(content().string(containsString("Ø 2,35")))
                .andExpect(content().string(containsString("Gesamtschnitt 2,35")))
                .andExpect(content().string(containsString("polyline")))
                .andExpect(content().string(containsString("Neue Note")));
    }

    @Test
    void childSeesOwnGradesWithoutEditForms() throws Exception {
        mockMvc.perform(get("/school/years/{id}/grades", year.getId()).param("term", "1").with(ownGrades("lena")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Brüche")))
                .andExpect(content().string(not(containsString("Neue Note"))))
                .andExpect(content().string(not(containsString("Zeugnisnote speichern"))));
    }

    @Test
    void siblingAndUsersWithoutRightsAreRefused() throws Exception {
        mockMvc.perform(get("/school/years/{id}/grades", year.getId()).with(ownGrades("tom")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/school/years/{id}/grades", year.getId())
                        .with(user("lena").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void childCannotAddGrades() throws Exception {
        mockMvc.perform(post("/school/years/{id}/grades", year.getId()).param("subjectId", math.getId().toString())
                        .param("term", "1").param("grade", "1").param("kind", "WRITTEN").param("day", "2026-10-10")
                        .with(csrf()).with(ownGrades("lena")))
                .andExpect(status().isForbidden());
        assertThat(grades.overview(year.getId(), 1, admin).subjects().getFirst().grades()).hasSize(2);
    }

    @Test
    void parentAddsAndDeletesGradesAndSetsReportGrade() throws Exception {
        mockMvc.perform(post("/school/years/{id}/grades", year.getId()).param("subjectId", math.getId().toString())
                        .param("term", "1").param("grade", "1-").param("weight", "200").param("kind", "WRITTEN")
                        .param("day", "2026-10-10").with(csrf()).with(allGrades()))
                .andExpect(flash().attributeExists("success"));
        var list = grades.overview(year.getId(), 1, admin).subjects().getFirst().grades();
        assertThat(list).hasSize(3);

        mockMvc.perform(post("/school/years/{id}/report-grades", year.getId())
                        .param("subjectId", math.getId().toString()).param("term", "1").param("grade", "2")
                        .with(csrf()).with(allGrades()))
                .andExpect(flash().attributeExists("success"));
        assertThat(grades.overview(year.getId(), 1, admin).subjects().getFirst().report().getLabel()).isEqualTo("2");

        mockMvc.perform(post("/school/grades/{id}/delete", list.getLast().getId())
                        .param("yearId", year.getId().toString()).param("term", "1").with(csrf()).with(allGrades()))
                .andExpect(flash().attributeExists("success"));
        assertThat(grades.overview(year.getId(), 1, admin).subjects().getFirst().grades()).hasSize(2);
    }

    @Test
    void invalidGradeShowsAnError() throws Exception {
        mockMvc.perform(post("/school/years/{id}/grades", year.getId()).param("subjectId", math.getId().toString())
                        .param("term", "1").param("grade", "7").param("kind", "WRITTEN").param("day", "2026-10-10")
                        .with(csrf()).with(allGrades()))
                .andExpect(flash().attribute("error", containsString("zwischen 1 und 6")));
    }

    @Test
    void overviewLinksToGradesOnlyWhenAllowed() throws Exception {
        mockMvc.perform(get("/school").with(allGrades()))
                .andExpect(content().string(containsString("/grades")));
        mockMvc.perform(get("/school").with(user("tom").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"))))
                .andExpect(content().string(not(containsString("/grades"))));
    }
}
