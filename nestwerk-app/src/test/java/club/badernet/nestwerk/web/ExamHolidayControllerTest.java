package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.ExamKind;
import club.badernet.nestwerk.school.ExamService;
import club.badernet.nestwerk.school.HolidayClient;
import club.badernet.nestwerk.school.HolidayException;
import club.badernet.nestwerk.school.HolidayService;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
class ExamHolidayControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService users;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired ExamService exams;
    @Autowired HolidayService holidays;
    @MockitoBean HolidayClient client;

    User admin;
    SchoolYear year;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        FamilyMember lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), "BY");
        year = school.startYear(profile.getId(), "2026/27", "Grundschule", "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
    }

    private static RequestPostProcessor viewer() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"));
    }

    private static RequestPostProcessor editor() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_EDIT"), new SimpleGrantedAuthority("SCHOOL_SETTINGS"));
    }

    @Test
    void createsAndListsAnExam() throws Exception {
        LocalDate day = LocalDate.now().plusDays(30);
        mockMvc.perform(post("/school/years/{id}/exams", year.getId()).param("kind", "EXAM")
                        .param("title", "Bruchrechnen").param("day", day.toString()).param("studyDays", "3")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        mockMvc.perform(get("/school/years/{id}/exams", year.getId()).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bruchrechnen")))
                .andExpect(content().string(containsString("Lernen ab 3 Tagen vorher")))
                .andExpect(content().string(containsString("Im Kalender")));
    }

    @Test
    void examFormErrorsAreShown() throws Exception {
        mockMvc.perform(post("/school/years/{id}/exams", year.getId()).param("kind", "EXAM")
                        .param("studyDays", "3").with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", containsString("Datum")));
    }

    @Test
    void viewerCannotCreateOrDeleteExams() throws Exception {
        mockMvc.perform(post("/school/years/{id}/exams", year.getId()).param("kind", "EXAM")
                        .param("day", "2026-10-20").with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        var exam = exams.create(year.getId(), null, ExamKind.TEST, "T", LocalDate.now().plusDays(5), 0, admin);
        mockMvc.perform(post("/school/exams/{id}/delete", exam.getId()).param("yearId", year.getId().toString())
                        .with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        assertThat(exams.list(year.getId())).hasSize(1);
    }

    @Test
    void editorDeletesAnExam() throws Exception {
        var exam = exams.create(year.getId(), null, ExamKind.TEST, "T", LocalDate.now().plusDays(5), 0, admin);

        mockMvc.perform(post("/school/exams/{id}/delete", exam.getId()).param("yearId", year.getId().toString())
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        assertThat(exams.list(year.getId())).isEmpty();
    }

    @Test
    void holidayPageListsStoredHolidays() throws Exception {
        holidays.addManual("BY", "Brückentag", LocalDate.now().plusDays(10), LocalDate.now().plusDays(10), admin);

        mockMvc.perform(get("/school/holidays").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bayern")))
                .andExpect(content().string(containsString("Brückentag")))
                .andExpect(content().string(containsString("eigener Eintrag")));
    }

    @Test
    void refreshButtonFetchesAndReportsFailures() throws Exception {
        when(client.fetch(eq("BY"), any(), any())).thenReturn(List.of(new HolidayClient.Entry("1", "Herbstferien",
                LocalDate.now().plusDays(20), LocalDate.now().plusDays(25))));
        mockMvc.perform(post("/school/holidays/BY/refresh").with(csrf()).with(editor()))
                .andExpect(flash().attribute("success", "Ferien aktualisiert."));

        when(client.fetch(eq("BY"), any(), any())).thenThrow(new HolidayException("Quelle nicht erreichbar", null));
        mockMvc.perform(post("/school/holidays/BY/refresh").with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", "Quelle nicht erreichbar"));

        mockMvc.perform(post("/school/holidays/XX/refresh").with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", "Unbekanntes Bundesland"));
        mockMvc.perform(post("/school/holidays/BY/refresh").with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualHolidayCanBeAddedAndDeleted() throws Exception {
        mockMvc.perform(post("/school/holidays").param("state", "BY").param("name", "Brückentag")
                        .param("startsOn", "2026-10-16").param("endsOn", "2026-10-16").with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        Long id = holidays.list("BY", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).getFirst().getId();

        mockMvc.perform(post("/school/holidays/{id}/delete", id).with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        assertThat(holidays.list("BY", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEmpty();
    }
}
