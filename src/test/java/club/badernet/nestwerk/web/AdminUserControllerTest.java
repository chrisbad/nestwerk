package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(authorities = "USER_MANAGE")
class AdminUserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;

    @Test
    void lastAdministratorCannotBeDisabled() throws Exception {
        users.deleteAll();
        users.flush();
        User solo = userService.createAdministrator("solo", "passwort1");

        mockMvc.perform(post("/admin/users/{id}/enabled", solo.getId()).with(csrf()).param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findById(solo.getId())).get().extracting(User::isEnabled).isEqualTo(true);
    }

    @Test
    void usersCanBeDisabledAndRolesChanged() throws Exception {
        User user = userService.create("normal", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/enabled", user.getId()).with(csrf()).param("enabled", "false"))
                .andExpect(flash().attributeExists("success"));

        assertThat(users.findById(user.getId())).get().extracting(User::isEnabled).isEqualTo(false);
    }

    @Test
    void passwordResetRejectsShortPassword() throws Exception {
        User user = userService.create("pw", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/password", user.getId()).with(csrf()).param("password", "kurz"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void ownAccountMayBeResetButStillValidated() throws Exception {
        User user = userService.create("selbst", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/password", user.getId()).with(csrf()).param("password", "ganz-neu-123"))
                .andExpect(flash().attributeExists("success"));
    }

    // --- Anlegen (klassisch und per htmx) ---

    @Test
    void classicPostRedirectsToList() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .param("username", "klassisch")
                        .param("password", "geheim1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));
    }

    @Test
    void htmxPostReturnsOnlyThePanel() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .header("HX-Request", "true")
                        .param("username", "htmx-kind")
                        .param("password", "geheim1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"users-panel\"")))
                .andExpect(content().string(containsString("htmx-kind")))
                .andExpect(content().string(containsString("data-toast=\"success\"")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void htmxPostWithShortPasswordReturnsError() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .header("HX-Request", "true")
                        .param("username", "zukurz")
                        .param("password", "kurz"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-form-error")))
                .andExpect(content().string(containsString("8 Zeichen")));
    }

    // --- Schutz vor Rechteausweitung ---

    private Long adminRoleId() {
        return roles.findByAllPermissionsTrue().orElseThrow().getId();
    }

    @Test
    void nonAdministratorCannotCreateAdministrator() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .param("username", "eindringling")
                        .param("password", "passwort1")
                        .param("roleIds", adminRoleId().toString()))
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findByUsername("eindringling")).isEmpty();
    }

    @Test
    void nonAdministratorCannotGrantPermissionsBeyondOwn() throws Exception {
        Role members = roles.save(new Role("Mitgliederpflege", null, Set.of("MEMBER_MANAGE")));
        User user = userService.create("normal2", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/roles", user.getId()).with(csrf())
                        .param("roleIds", members.getId().toString()))
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findById(user.getId())).get().extracting(User::getRoles).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.COLLECTION).isEmpty();
    }

    @Test
    void nonAdministratorCannotResetAdministratorPassword() throws Exception {
        User admin = userService.createAdministrator("oberhaupt", "passwort1");
        String before = admin.getPasswordHash();

        mockMvc.perform(post("/admin/users/{id}/password", admin.getId()).with(csrf())
                        .param("password", "uebernommen1"))
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findById(admin.getId())).get().extracting(User::getPasswordHash).isEqualTo(before);
    }

    @Test
    @WithMockUser(authorities = {"USER_MANAGE", "ACCESS_ROLE_MANAGE", "MEMBER_MANAGE",
            "CALENDAR_VIEW", "CALENDAR_EVENT_CREATE", "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_EDIT_ALL",
            "CALENDAR_EVENT_DELETE_OWN", "CALENDAR_EVENT_DELETE_ALL", "CALENDAR_PRIVATE_CREATE",
            "NOTIFICATION_SETTINGS_MANAGE", "HOUSEHOLD_DEVICE_MANAGE", "AI_ACCESS"})
    void administratorCanCreateAdministrator() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .param("username", "zweiter-admin")
                        .param("password", "passwort1")
                        .param("roleIds", adminRoleId().toString()))
                .andExpect(flash().attributeExists("success"));

        assertThat(users.findByUsername("zweiter-admin")).get()
                .extracting(User::isAdministrator).isEqualTo(true);
    }

    @Test
    void nonAdministratorCannotTakeOverUserWithMorePermissions() throws Exception {
        Role roleAdmins = roles.save(new Role("Rollenpflege", null, Set.of("ACCESS_ROLE_MANAGE")));
        User target = userService.create("rollenwart", "passwort1", Set.of(roleAdmins.getId()));
        String before = target.getPasswordHash();

        mockMvc.perform(post("/admin/users/{id}/password", target.getId()).with(csrf())
                        .param("password", "uebernommen1"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/admin/users/{id}/enabled", target.getId()).with(csrf())
                        .param("enabled", "false"))
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findById(target.getId())).get().extracting(User::getPasswordHash).isEqualTo(before);
        assertThat(users.findById(target.getId())).get().extracting(User::isEnabled).isEqualTo(true);
    }

    @Test
    void nonAdministratorCannotAssignRoleWithForeignAccessRules() throws Exception {
        User chef = userService.createAdministrator("regelchef", "passwort1");
        Role regeln = roles.save(new Role("Nur Regeln", null, Set.of()));
        acl.replaceRules(chef, regeln.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));

        mockMvc.perform(post("/admin/users").with(csrf())
                        .param("username", "regelkind").param("password", "passwort1")
                        .param("roleIds", regeln.getId().toString()))
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findByUsername("regelkind")).isEmpty();
    }
}
