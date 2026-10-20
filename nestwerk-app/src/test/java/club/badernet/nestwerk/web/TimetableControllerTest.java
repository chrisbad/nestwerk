package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.Lesson;
import club.badernet.nestwerk.school.LessonInput;
import club.badernet.nestwerk.school.PeriodKind;
import club.badernet.nestwerk.school.PeriodService;
import club.badernet.nestwerk.school.SchoolContacts;
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
import java.util.List;

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
    @Autowired PeriodService periods;
    @Autowired SchoolContacts schoolContacts;

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
        year = school.startYear(profile.getId(), "2026/27", null, "3a", MONDAY, LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", "#4d74ff", admin);
        timetable.addTeacher(math.getId(), "Frau Weber", "weber@schule.de", admin);
        lesson = timetable.addLesson(year.getId(), LessonInput.timed(math.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0),
                LocalTime.of(8, 45), "R12", WeekKind.EVERY), admin);
    }

    private static RequestPostProcessor viewer() {
        return user("mama").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"));
    }

    private static RequestPostProcessor editor() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_EDIT"));
    }

    private void gridWithBreak() {
        var park = schoolContacts.createSchool(new SchoolContacts.SchoolInput("Schule am Park", null, null, null,
                null, null), admin);
        school.changeYear(year.getId(), year.getLabel(), park.getId(), year.getClassName(), year.getStartsOn(),
                year.getEndsOn());
        periods.saveGrid(park.getId(), List.of(
                new PeriodService.PeriodInput(PeriodKind.LESSON, LocalTime.of(8, 0), LocalTime.of(8, 45), null),
                new PeriodService.PeriodInput(PeriodKind.LESSON, LocalTime.of(8, 45), LocalTime.of(9, 30), null),
                new PeriodService.PeriodInput(PeriodKind.BREAK, LocalTime.of(9, 30), LocalTime.of(9, 50), null),
                new PeriodService.PeriodInput(PeriodKind.LESSON, LocalTime.of(9, 50), LocalTime.of(10, 35), null)),
                admin);
    }

    @Test
    void shownWeekSwitchesToTheNextOneOnSaturday() {
        LocalDate friday = LocalDate.of(2026, 10, 23);

        assertThat(TimetableController.shownMonday(null, friday)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(TimetableController.shownMonday(null, friday.plusDays(1))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(TimetableController.shownMonday(null, friday.plusDays(2))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(TimetableController.shownMonday(friday.plusDays(1), friday)).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    void weekViewShowsLessonsWithRoomAndTeacherButNoSettings() throws Exception {
        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-05").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Schule Lena")))
                .andExpect(content().string(containsString("Mathe")))
                .andExpect(content().string(containsString("R12")))
                .andExpect(content().string(containsString("mailto:weber@schule.de")))
                .andExpect(content().string(not(containsString("/school/settings/"))))
                .andExpect(content().string(not(containsString("tt-cancel-button"))));
    }

    @Test
    void editorSeesQuickCancellationAndTheSettingsMenu() throws Exception {
        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-03").with(editor()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tt-cancel-button")))
                .andExpect(content().string(containsString("Raumwechsel")))
                .andExpect(content().string(containsString("/school/settings/lessons?year=" + year.getId())))
                .andExpect(content().string(not(containsString("Neues Fach"))));
    }

    @Test
    void gridShowsPeriodsBreaksAndEmptySlots() throws Exception {
        gridWithBreak();
        timetable.changeLesson(lesson.getId(), LessonInput.inPeriods(math.getId(), DayOfWeek.MONDAY, 1, 2, "R12",
                WeekKind.EVERY), admin);

        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-03").with(editor()))
                .andExpect(content().string(containsString("grid-row: 2 / 4; grid-column: 2")))
                .andExpect(content().string(containsString("tt-pause")))
                .andExpect(content().string(containsString("Schule am Park")))
                .andExpect(content().string(containsString("weekday=MONDAY&amp;period=3")));
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
                .andExpect(content().string(containsString("tt-cancelled")));
    }

    @Test
    void roomChangeShowsTheNewRoom() throws Exception {
        mockMvc.perform(post("/school/lessons/{id}/changes", lesson.getId()).param("yearId", year.getId().toString())
                        .param("day", "2026-08-03").param("kind", "ROOM").param("note", "Aula")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        mockMvc.perform(get("/school/years/{id}/timetable", year.getId()).param("week", "2026-08-03").with(viewer()))
                .andExpect(content().string(containsString("tt-hint\">Aula<")));
    }

    @Test
    void viewerCannotMarkLessons() throws Exception {
        mockMvc.perform(post("/school/lessons/{id}/changes", lesson.getId()).param("yearId", year.getId().toString())
                        .param("day", "2026-08-03").param("kind", "CANCELLED").with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
    }
}
