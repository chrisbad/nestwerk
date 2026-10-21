package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CategoryColor;
import club.badernet.nestwerk.calendar.CategoryInput;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
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

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class DrawerFragmentsTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberRepository members;
    @Autowired EventCategoryService categories;

    User person;
    FamilyMember member;
    EventCategory category;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Familie", null, Set.of("CALENDAR_VIEW", "CALENDAR_CATEGORY_MANAGE"));
        person = userService.create("lena", "passwort1", Set.of(normal.getId()));
        member = members.save(new FamilyMember("Lena", "#00aa00", person.getId()));
        category = categories.create(new CategoryInput("Musikschule", CategoryColor.BLUE), person);
    }

    private static RequestPostProcessor authorities(String... names) {
        return user("lena").authorities(java.util.Arrays.stream(names).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void userDetailsComeAsFragmentWithoutThePageAround() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/edit", person.getId()).with(authorities("USER_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/users/" + person.getId() + "/roles")))
                .andExpect(content().string(containsString("Passwort zurücksetzen")))
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(not(containsString("topnav"))));
    }

    @Test
    void userDetailsNeedTheirPermissionAndAnExistingUser() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/edit", person.getId()).with(authorities("MEMBER_MANAGE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users/{id}/edit", 999_999).with(authorities("USER_MANAGE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void usersListOpensTheDrawerInsteadOfCarryingADialogPerRow() throws Exception {
        mockMvc.perform(get("/admin/users").with(authorities("USER_MANAGE")))
                .andExpect(content().string(containsString("data-drawer-url=\"/admin/users/" + person.getId() + "/edit\"")))
                .andExpect(content().string(not(containsString("edit-user-"))));
    }

    @Test
    void memberDetailsComeAsFragment() throws Exception {
        mockMvc.perform(get("/admin/members/{id}/edit", member.getId()).with(authorities("MEMBER_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/members/" + member.getId() + "/delete")))
                .andExpect(content().string(containsString("value=\"Lena\"")));
        mockMvc.perform(get("/admin/members/{id}/edit", 999_999).with(authorities("MEMBER_MANAGE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryDetailsKeepTheOriginInTheirForms() throws Exception {
        mockMvc.perform(get("/calendar/categories/{id}/edit", category.getId())
                        .param("back", "/calendar/week?date=2026-10-21").with(authorities("CALENDAR_CATEGORY_MANAGE", "CALENDAR_VIEW")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Musikschule\"")))
                .andExpect(content().string(containsString("name=\"back\"")))
                .andExpect(content().string(containsString("/calendar/categories/" + category.getId() + "/delete")));
    }

    @Test
    void categoriesListPointsAtTheDrawerAndPassesTheOrigin() throws Exception {
        mockMvc.perform(get("/calendar/categories").param("back", "/calendar/week").with(authorities("CALENDAR_CATEGORY_MANAGE", "CALENDAR_VIEW")))
                .andExpect(content().string(containsString("data-drawer-url=\"/calendar/categories/" + category.getId() + "/edit?back=")))
                .andExpect(content().string(not(containsString("edit-category-"))));
    }

    @Test
    void restoreFormNeedsTheBackupPermission() throws Exception {
        mockMvc.perform(get("/admin/backup/files/{name}/restore-form", "nestwerk-x.zip").with(authorities("USER_MANAGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyPageCarriesTheOneDrawer() throws Exception {
        mockMvc.perform(get("/manual").with(authorities("CALENDAR_VIEW")))
                .andExpect(content().string(containsString("id=\"drawer\"")))
                .andExpect(content().string(containsString("data-drawer-body")));
    }
}
