package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.Lesson;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
class TimetableControllerTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);

    @Autowired MockMvc mockMvc;
    @Autowired UserService users;
    @Autowired club.badernet.nestwerk.role.RoleRepository roles;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;

    User admin;
    SchoolYear year;
    SchoolSubject math;
    Lesson lesson;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        users.create("mama", "passwort1", java.util.Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        FamilyMember lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", "Grundschule", "3a", MONDAY, LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", "#4d74ff", "Frau Weber", admin);
        lesson = timetable.addLesson(year.getId(), math.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0),
                LocalTime.of(8, 45), "R12", WeekKind.EVERY, admin);
    }

    private static RequestPostProcessor viewer() {
        return user("mama").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"));
    }

    private static RequestPostProcessor editor() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_EDIT"));
    }

    @Test
    void weekViewShowsLessonsWithRoomAndTeacher() throws Exception {
        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-05").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stundenplan Lena")))
                .andExpect(content().string(containsString("Mathe")))
                .andExpect(content().string(containsString("08:00–08:45")))
                .andExpect(content().string(containsString("Raum R12")))
                .andExpect(content().string(containsString("Frau Weber")))
                .andExpect(content().string(not(containsString("Neues Fach"))));
    }

    @Test
    void editorSeesManagementForms() throws Exception {
        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-03").with(editor()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Neues Fach")))
                .andExpect(content().string(containsString("A/B-Wochen")))
                .andExpect(content().string(containsString("Ausfall / Vertretung")));
    }

    @Test
    void unknownYearIsNotFound() throws Exception {
        mockMvc.perform(get("/school/years/{id}/timetable", 999_999).with(viewer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingALessonIsShownStruckThrough() throws Exception {
        mockMvc.perform(post("/school/lessons/{id}/changes", lesson.getId()).param("yearId", year.getId().toString())
                        .param("day", "2026-08-03").param("kind", "CANCELLED").param("note", "Lehrerin krank")
                        .param("week", "2026-08-03").with(csrf()).with(editor()))
                .andExpect(redirectedUrl("/school/years/" + year.getId() + "/timetable?week=2026-08-03"))
                .andExpect(flash().attributeExists("success"));

        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-03").with(viewer()))
                .andExpect(content().string(containsString("Entfällt: Lehrerin krank")))
                .andExpect(content().string(containsString("struck")));
    }

    @Test
    void viewerCannotEdit() throws Exception {
        mockMvc.perform(post("/school/years/{id}/subjects", year.getId()).param("name", "Kunst")
                        .with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        assertThat(timetable.subjects(year.getId())).hasSize(1);
    }

    @Test
    void invalidLessonShowsAnError() throws Exception {
        mockMvc.perform(post("/school/years/{id}/lessons", year.getId()).param("subjectId", math.getId().toString())
                        .param("weekday", "MONDAY").param("startsAt", "08:30").param("endsAt", "09:00")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", containsString("überschneidet")));
    }

    @Test
    void addsSubjectAndLessonThroughTheForms() throws Exception {
        mockMvc.perform(post("/school/years/{id}/subjects", year.getId()).param("name", "Kunst")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        SchoolSubject art = timetable.subjects(year.getId()).stream().filter(s -> s.getName().equals("Kunst"))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/school/years/{id}/lessons", year.getId()).param("subjectId", art.getId().toString())
                        .param("weekday", "TUESDAY").param("startsAt", "09:00").param("endsAt", "09:45")
                        .param("weekKind", "EVERY").with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        assertThat(timetable.lessons(year.getId())).hasSize(2);
    }
}
