package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreRepository;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class HouseholdControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired ChoreService chores;
    @Autowired ChoreRepository choreRepository;
    @Autowired ChoreTaskRepository tasks;
    @Autowired CalendarEventRepository events;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User mama;
    User kid;
    FamilyMember mamaM;
    FamilyMember kidM;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        roleService.create("Leer", null, Set.of());
        mama = userService.create("mama", "passwort1", Set.of(benutzer, eltern.getId()));
        kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        userService.create("fremd", "passwort1", Set.of(roles.findByNameIgnoreCase("Leer").orElseThrow().getId()));
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        kidM = members.save(new FamilyMember("Lena", "#00aa00", kid.getId()));
        today = LocalDate.now(clock);
    }

    private static RequestPostProcessor as(String name) {
        return user(name).authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"),
                new SimpleGrantedAuthority("CALENDAR_EVENT_CREATE"));
    }

    private ChoreTask todaysTask(String title, Long memberId) {
        Chore c = chores.create(new ChoreInput(title, "Küche", null, 15, ChoreEffort.HARD, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(memberId)), admin);
        entityManager.flush();
        return tasks.findAllByChoreId(c.getId()).getFirst();
    }

    @Test
    void weekShowsTasksInTheMembersRow() throws Exception {
        todaysTask("Spülmaschine ausräumen", kidM.getId());

        mockMvc.perform(get("/household").with(as("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Spülmaschine ausräumen")))
                .andExpect(content().string(containsString("week-grid")))
                .andExpect(content().string(containsString("effort-hard")))
                .andExpect(content().string(containsString("Lena")));
    }

    @Test
    void usersWithoutViewRuleAreForbidden() throws Exception {
        mockMvc.perform(get("/household").with(as("fremd"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/").with(as("fremd")))
                .andExpect(content().string(not(containsString("href=\"/household\""))));
        mockMvc.perform(get("/").with(as("kind")))
                .andExpect(content().string(containsString("href=\"/household\"")));
    }

    @Test
    void completingWithHtmxReturnsTheUpdatedChip() throws Exception {
        ChoreTask t = todaysTask("Müll", kidM.getId());

        mockMvc.perform(post("/household/tasks/{id}/complete", t.getId()).with(csrf()).with(as("kind"))
                        .header("HX-Request", "true").param("back", "/household"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is-done")))
                .andExpect(content().string(containsString("data-toast=\"success\"")))
                .andExpect(content().string(containsString("/household/tasks/" + t.getId() + "/undo")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void completingWithoutHtmxRedirectsBack() throws Exception {
        ChoreTask t = todaysTask("Müll", kidM.getId());

        mockMvc.perform(post("/household/tasks/{id}/complete", t.getId()).with(csrf()).with(as("kind"))
                        .param("back", "/household/today"))
                .andExpect(redirectedUrl("/household/today"));
        mockMvc.perform(post("/household/tasks/{id}/complete", t.getId()).with(csrf()).with(as("kind"))
                        .param("back", "https://evil.example"))
                .andExpect(redirectedUrl("/household/tasks/" + t.getId()));
    }

    @Test
    void childCannotCompleteSomebodyElsesTask() throws Exception {
        ChoreTask t = todaysTask("Bad", mamaM.getId());

        mockMvc.perform(post("/household/tasks/{id}/complete", t.getId()).with(csrf()).with(as("kind")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsChoreFromFormInSelectedOrder() throws Exception {
        mockMvc.perform(post("/household/chores").with(csrf()).with(as("mama"))
                        .param("title", "Bad putzen").param("minutes", "30").param("effort", "HARD")
                        .param("kind", "INTERVAL").param("intervalDays", "7").param("startsOn", today.toString())
                        .param("distribution", "ROTATION")
                        .param("memberIds", kidM.getId().toString()).param("memberIds", mamaM.getId().toString()))
                .andExpect(status().is3xxRedirection());

        Chore c = choreRepository.findAll().stream().filter(x -> x.getTitle().equals("Bad putzen")).findFirst().orElseThrow();
        assertThat(c.getMemberIds()).containsExactly(kidM.getId(), mamaM.getId());
        assertThat(c.getIntervalDays()).isEqualTo(7);
    }

    @Test
    void formShowsValidationError() throws Exception {
        mockMvc.perform(post("/household/chores").with(csrf()).with(as("mama"))
                        .param("title", "Bad").param("minutes", "0").param("effort", "EASY")
                        .param("kind", "INTERVAL").param("intervalDays", "7").param("distribution", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Minuten")))
                .andExpect(content().string(containsString("value=\"Bad\"")));
    }

    @Test
    void childCannotCreateChores() throws Exception {
        mockMvc.perform(get("/household/chores/new").with(as("kind"))).andExpect(status().isForbidden());
    }

    @Test
    void taskDetailOffersHelpersAndChoreDetailShowsUpcoming() throws Exception {
        ChoreTask t = todaysTask("Fenster putzen", kidM.getId());

        mockMvc.perform(get("/household/tasks/{id}", t.getId()).with(as("kind")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mitgeholfen haben")))
                .andExpect(content().string(containsString("name=\"helpers\"")));
        mockMvc.perform(get("/household/chores/{id}", t.getChore().getId()).with(as("kind")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Die nächsten drei Wochen")))
                .andExpect(content().string(not(containsString("/edit"))));
    }

    @Test
    void todayAndHomeTileListOwnTasks() throws Exception {
        todaysTask("Zimmer aufräumen", kidM.getId());
        todaysTask("Steuer", mamaM.getId());

        mockMvc.perform(get("/household/today").with(as("kind")))
                .andExpect(content().string(containsString("Zimmer aufräumen")))
                .andExpect(content().string(not(containsString("Steuer"))));
        mockMvc.perform(get("/").with(as("kind")))
                .andExpect(content().string(containsString("Meine Hausarbeiten")))
                .andExpect(content().string(containsString("Zimmer aufräumen")));
    }

    @Test
    void addingAndRemovingFixedDates() throws Exception {
        Chore c = chores.create(new ChoreInput("Gelbe Tonne", null, null, 5, ChoreEffort.EASY, ChoreKind.DATES,
                null, null, null, null, Distribution.FIXED, List.of(kidM.getId())), admin);

        mockMvc.perform(post("/household/chores/{id}/dates", c.getId()).with(csrf()).with(as("mama"))
                        .param("day", today.plusDays(2).toString()).param("time", "19:00"))
                .andExpect(redirectedUrl("/household/chores/" + c.getId() + "#dates"));

        mockMvc.perform(get("/household/chores/{id}", c.getId()).with(as("mama")))
                .andExpect(content().string(containsString("19:00")))
                .andExpect(content().string(containsString("Termin hinzufügen")));
    }

    @Test
    void calendarEventCanBeMarkedAsAbsence() throws Exception {
        mockMvc.perform(get("/calendar/events/new").with(as("mama")))
                .andExpect(content().string(containsString("name=\"absence\"")));

        mockMvc.perform(post("/calendar/events").with(csrf()).with(as("mama"))
                        .param("title", "Skifreizeit").param("allDay", "true").param("absence", "true")
                        .param("startDate", today.toString()).param("endDate", today.plusDays(2).toString())
                        .param("memberIds", kidM.getId().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(events.findAllByAbsenceTrue()).extracting(e -> e.getTitle()).containsExactly("Skifreizeit");
    }
}
