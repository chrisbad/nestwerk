package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.HomeworkService;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.todo.Todo;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
class HomeworkControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService users;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired HomeworkService homework;

    User admin;
    SchoolYear year;
    SchoolSubject german;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        FamilyMember lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", "Grundschule", "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
        german = timetable.addSubject(year.getId(), "Deutsch", "#e07a3f", null, admin);
    }

    private static RequestPostProcessor homeworkUser() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_HOMEWORK"));
    }

    @Test
    void pageListsOpenHomeworkAndTheForm() throws Exception {
        homework.create(year.getId(), german.getId(), "Seite 12 lesen", null, LocalDate.of(2026, 9, 10), null, admin);

        mockMvc.perform(get("/school/years/{id}/homework", year.getId()).with(homeworkUser()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hausaufgaben Lena")))
                .andExpect(content().string(containsString("Seite 12 lesen")))
                .andExpect(content().string(containsString("fällig 10.09.")))
                .andExpect(content().string(containsString("Neue Hausaufgabe")));
    }

    @Test
    void createsAndCompletesHomework() throws Exception {
        mockMvc.perform(post("/school/years/{id}/homework", year.getId()).param("title", "Vokabeln")
                        .param("subjectId", german.getId().toString()).param("dueOn", "2026-09-10")
                        .param("repeat", "WEEKLY").with(csrf()).with(homeworkUser()))
                .andExpect(flash().attributeExists("success"));
        Todo todo = homework.list(year.getId(), null, false, admin).getFirst().todo();
        assertThat(todo.getRrule()).isEqualTo("FREQ=WEEKLY");

        mockMvc.perform(post("/school/homework/{id}/done", todo.getId()).param("yearId", year.getId().toString())
                        .with(csrf()).with(homeworkUser()))
                .andExpect(flash().attributeExists("success"));
        assertThat(homework.list(year.getId(), null, false, admin).getFirst().todo().getDueOn())
                .isEqualTo(LocalDate.of(2026, 9, 17));
    }

    @Test
    void rejectsUnknownRepeatAndMissingRight() throws Exception {
        mockMvc.perform(post("/school/years/{id}/homework", year.getId()).param("title", "x")
                        .param("dueOn", "2026-09-10").param("repeat", "HOURLY").with(csrf()).with(homeworkUser()))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/school/years/{id}/homework", year.getId()).param("title", "x")
                        .param("dueOn", "2026-09-10").with(csrf())
                        .with(user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"))))
                .andExpect(status().isForbidden());
        assertThat(homework.list(year.getId(), null, false, admin)).isEmpty();
    }

    @Test
    void doneListShowsCompletedHomework() throws Exception {
        Todo todo = homework.create(year.getId(), null, "Fertig", null, LocalDate.of(2026, 9, 10), null, admin);
        homework.setDone(todo.getId(), true, admin);

        mockMvc.perform(get("/school/years/{id}/homework", year.getId()).param("done", "true").with(homeworkUser()))
                .andExpect(content().string(containsString("Fertig")))
                .andExpect(content().string(containsString("Wieder öffnen")));
    }
}
