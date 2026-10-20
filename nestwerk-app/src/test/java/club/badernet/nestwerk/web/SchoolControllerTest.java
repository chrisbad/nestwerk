package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.school.PeriodService;
import club.badernet.nestwerk.school.SchoolContacts;
import club.badernet.nestwerk.school.SchoolPeriod;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class SchoolControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired PeriodService periods;
    @Autowired SchoolContacts schoolContacts;

    User admin;
    FamilyMember lena;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        userService.create("mama", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
    }

    private static RequestPostProcessor viewer() {
        return user("mama").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"));
    }

    private static RequestPostProcessor editor() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_EDIT"), new SimpleGrantedAuthority("SCHOOL_SETTINGS"));
    }

    private SchoolYear yearOf(FamilyMember member) {
        SchoolProfile profile = school.createProfile(member.getId(), "BY");
        return school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
    }

    @Test
    void pageNeedsTheViewRight() throws Exception {
        mockMvc.perform(get("/school").with(user("mama").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/school").with(viewer())).andExpect(status().isOk());
    }

    @Test
    void withoutChildrenEditorsLandInTheSettings() throws Exception {
        mockMvc.perform(get("/school").with(editor())).andExpect(redirectedUrl("/school/settings/years"));
    }

    @Test
    void entryOpensTheTimetableOfTheChosenAndThenTheRememberedChild() throws Exception {
        SchoolYear lenaYear = yearOf(lena);
        FamilyMember tom = members.create("Tom", "#112233", null);
        SchoolYear tomYear = yearOf(tom);
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/school").with(viewer()).session(session))
                .andExpect(redirectedUrl("/school/years/" + lenaYear.getId() + "/timetable"));
        mockMvc.perform(get("/school").param("child", tom.getId().toString()).with(viewer()).session(session))
                .andExpect(redirectedUrl("/school/years/" + tomYear.getId() + "/timetable"));
        mockMvc.perform(get("/school/years/{id}/timetable", tomYear.getId()).with(viewer()).session(session))
                .andExpect(content().string(containsString("child-chip")));
        mockMvc.perform(get("/school").with(viewer()).session(session))
                .andExpect(redirectedUrl("/school/years/" + tomYear.getId() + "/timetable"));
    }

    @Test
    void editorCreatesProfileAndYearWithANewSchool() throws Exception {
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString()).param("state", "BY")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();

        mockMvc.perform(post("/school/profiles/{id}/years", profile.getId())
                        .param("label", "2026/27").param("newSchoolName", "Grundschule am Park")
                        .param("newSchoolPhone", "0911 123").param("newSchoolEmail", "sekretariat@park.de")
                        .param("className", "3a").param("startsOn", "2026-08-01").param("endsOn", "2027-07-31")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        SchoolYear year = school.activeYear(profile.getId()).orElseThrow();
        assertThat(year.getClassName()).isEqualTo("3a");
        assertThat(schoolContacts.get(year.getSchoolContactId()).orElseThrow().getCategory()).isEqualTo("Schule");
        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).with(viewer()))
                .andExpect(content().string(containsString("Grundschule am Park")))
                .andExpect(content().string(containsString("Klasse 3a")))
                .andExpect(content().string(containsString("mailto:sekretariat@park.de")));
    }

    @Test
    void settingsPagesNeedTheEditRight() throws Exception {
        SchoolYear year = yearOf(lena);
        mockMvc.perform(get("/school/settings/lessons").param("year", year.getId().toString()).with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/school/settings/lessons").param("year", year.getId().toString()).with(editor()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A/B-Wochen")));
        mockMvc.perform(get("/school/settings/years").with(viewer())).andExpect(status().isOk());
    }

    @Test
    void savesThePeriodGridWithBreaks() throws Exception {
        SchoolYear year = yearOf(lena);
        mockMvc.perform(post("/school/years/{id}/school", year.getId()).param("newSchoolName", "Schule am Park")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        Long schoolId = school.year(year.getId()).orElseThrow().getSchoolContactId();

        mockMvc.perform(post("/school/years/{id}/grid", year.getId())
                        .param("kind", "LESSON", "BREAK", "LESSON", "LESSON")
                        .param("startsAt", "08:00", "08:45", "09:05", "")
                        .param("endsAt", "08:45", "09:05", "09:50", "")
                        .param("label", "", "Hofpause", "", "")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        assertThat(periods.grid(schoolId)).extracting(SchoolPeriod::displayName)
                .containsExactly("1. Stunde", "Hofpause", "2. Stunde");
    }

    @Test
    void addsATeacherContactToASubject() throws Exception {
        SchoolYear year = yearOf(lena);
        SchoolSubject math = timetable.addSubject(year.getId(), "Mathe", null, admin);

        mockMvc.perform(post("/school/subjects/{id}/teachers/new", math.getId())
                        .param("yearId", year.getId().toString())
                        .param("name", "Frau Weber").param("email", "weber@schule.de").with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        assertThat(timetable.teachers(year.getId()).get(math.getId())).extracting(c -> c.getEmail())
                .containsExactly("weber@schule.de");
        mockMvc.perform(get("/school/settings/subjects").param("year", year.getId().toString()).with(editor()))
                .andExpect(content().string(containsString("Frau Weber")));
    }

    @Test
    void addsALessonInTheGridThroughTheForm() throws Exception {
        SchoolYear year = yearOf(lena);
        SchoolSubject math = timetable.addSubject(year.getId(), "Mathe", null, admin);
        var park = schoolContacts.createSchool(new SchoolContacts.SchoolInput("Park", null, null, null, null, null),
                admin);
        school.changeYear(year.getId(), year.getLabel(), park.getId(), "3a", year.getStartsOn(), year.getEndsOn());
        periods.saveGrid(park.getId(), PeriodService.suggestion(), admin);

        mockMvc.perform(post("/school/years/{id}/lessons", year.getId()).param("subjectId", math.getId().toString())
                        .param("weekday", "TUESDAY").param("mode", "grid").param("periodFrom", "3")
                        .param("periodTo", "4").param("weekKind", "EVERY").with(csrf()).with(editor()))
                .andExpect(redirectedUrl("/school/settings/lessons?year=" + year.getId()))
                .andExpect(flash().attributeExists("success"));

        assertThat(timetable.lessons(year.getId())).singleElement()
                .satisfies(l -> assertThat(l.getPeriodTo()).isEqualTo(4));
    }

    @Test
    void invalidInputShowsAnError() throws Exception {
        school.createProfile(lena.getId(), null);
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString())
                        .with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", containsString("schon ein Schulprofil")));
    }

    @Test
    void viewerCannotChangeAnything() throws Exception {
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString())
                        .with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        assertThat(school.profileOf(lena.getId())).isEmpty();
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString()).with(editor()))
                .andExpect(status().isForbidden());
    }
}
