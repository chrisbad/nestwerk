package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.household.DeviceService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.school.GradeKind;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.HomeworkService;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class SchoolTabletHomeTest {

    @Autowired MockMvc mockMvc;
    @Autowired DeviceService devices;
    @Autowired UserService users;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired HomeworkService homework;
    @Autowired GradeService grades;
    @Autowired Clock clock;

    User admin;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        users.create("mama", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        LocalDate today = LocalDate.now(clock);
        FamilyMember lena = members.create("Lena", "#aabbcc", null);
        SchoolYear year = school.startYear(school.createProfile(lena.getId(), null).getId(), "aktuell", "Grundschule",
                "3a", today.minusMonths(1), today.plusMonths(6));
        SchoolSubject math = timetable.addSubject(year.getId(), "Mathe", "#4d74ff", null, admin);
        timetable.addLesson(year.getId(), math.getId(), today.getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(8, 45),
                "R12", WeekKind.EVERY, admin);
        homework.create(year.getId(), math.getId(), "Seite 12 lesen", null, today, null, admin);
        grades.add(year.getId(), math.getId(), 1, "2+", 100, GradeKind.WRITTEN, today, "geheime Note", admin);
    }

    @Test
    void tabletShowsSchoolTodayButNeverGrades() throws Exception {
        Cookie cookie = pair();

        mockMvc.perform(get("/tablet").cookie(cookie))
                .andExpect(content().string(containsString("Schule heute")))
                .andExpect(content().string(containsString("Mathe")))
                .andExpect(content().string(containsString("08:00–08:45")))
                .andExpect(content().string(containsString("Seite 12 lesen")))
                .andExpect(content().string(not(containsString("geheime Note"))))
                .andExpect(content().string(not(containsString("2+"))));
    }

    @Test
    void homeTileShowsTheSchoolDayForUsersWithSchoolRight() throws Exception {
        mockMvc.perform(get("/").with(user("mama")))
                .andExpect(content().string(containsString("Heute in der Schule")))
                .andExpect(content().string(containsString("Lena")))
                .andExpect(content().string(containsString("Seite 12 lesen")))
                .andExpect(content().string(containsString("data-area=\"school\"")))
                .andExpect(content().string(not(containsString("geheime Note"))));
    }

    @Test
    void usersWithoutSchoolRightSeeNoSchoolTile() throws Exception {
        users.create("fremd", "passwort1", Set.of());

        mockMvc.perform(get("/").with(user("fremd")))
                .andExpect(content().string(not(containsString("Heute in der Schule"))))
                .andExpect(content().string(not(containsString("data-area=\"school\""))));
    }

    @Test
    void navigationLinksToSchoolOnlyWithTheRight() throws Exception {
        mockMvc.perform(get("/manual").with(user("mama").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"))))
                .andExpect(content().string(containsString("href=\"/school\"")));
        mockMvc.perform(get("/manual").with(user("mama").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(not(containsString("href=\"/school\""))));
    }

    private Cookie pair() throws Exception {
        DeviceService.Pairing pairing = devices.create("Küche", admin);
        return mockMvc.perform(post("/tablet/pair").with(csrf()).param("code", pairing.code().toLowerCase()))
                .andReturn().getResponse().getCookie(TabletController.COOKIE);
    }
}
