package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
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
class AdminRoleAclTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;

    User admin;
    Role kind;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chefin", "passwort1");
        kind = roleService.create("Kind", null, Set.of());
    }

    /** Checkbox mit diesem Wert und Attribut (Reihenfolge und Zeilenumbrüche egal). */
    private static org.hamcrest.Matcher<String> checkbox(String value, String attribute) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<input[^>]*value=\"" + java.util.regex.Pattern.quote(value) + "\"[^>]*\\b" + attribute + "=");
        return new org.hamcrest.TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(String body) {
                return pattern.matcher(body).find();
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("Checkbox " + value + " mit " + attribute);
            }
        };
    }

    private static RequestPostProcessor as(String username) {
        return user(username).authorities(new SimpleGrantedAuthority("ACCESS_ROLE_MANAGE"));
    }

    @Test
    void showsMatrixWithSeededRules() throws Exception {
        mockMvc.perform(get("/admin/roles").with(as("chefin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zugriffsregeln")))
                .andExpect(content().string(containsString("Gast (nur lesen)")))
                .andExpect(content().string(checkbox("TODO:VIEW:ANY", "checked")));
    }

    @Test
    void newRoleWithoutRulesRendersMatrix() throws Exception {
        mockMvc.perform(get("/admin/roles").with(as("chefin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/roles/" + kind.getId() + "/acl")));
    }

    @Test
    void savesRulesFromMatrix() throws Exception {
        mockMvc.perform(post("/admin/roles/{id}/acl", kind.getId()).with(csrf()).with(as("chefin"))
                        .param("rules", "TODO:VIEW:ASSIGNED", "TODO:CHANGE_STATUS:ASSIGNED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(acl.rulesOf(kind.getId())).containsExactlyInAnyOrder(
                new AclRule("TODO", "VIEW", "ASSIGNED"), new AclRule("TODO", "CHANGE_STATUS", "ASSIGNED"));
    }

    @Test
    void appliesPreset() throws Exception {
        mockMvc.perform(post("/admin/roles/{id}/acl/preset", kind.getId()).with(csrf()).with(as("chefin"))
                        .param("preset", "GAST"))
                .andExpect(flash().attributeExists("success"));

        assertThat(acl.rulesOf(kind.getId())).hasSize(2);
    }

    @Test
    void malformedRuleShowsError() throws Exception {
        for (String bad : new String[]{"TODO:EDIT", "X:Y:Z", "TODO:CREATE:RESPONSIBLE"}) {
            mockMvc.perform(post("/admin/roles/{id}/acl", kind.getId()).with(csrf()).with(as("chefin"))
                            .param("rules", bad))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("error"));
        }
        assertThat(acl.rulesOf(kind.getId())).isEmpty();
    }

    @Test
    void nonAdministratorSeesForeignCellsDisabledAndCannotGrantThem() throws Exception {
        Role verwalter = roleService.create("Verwalter", null, Set.of("ACCESS_ROLE_MANAGE"));
        acl.replaceRules(admin, verwalter.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));
        userService.create("verwalter", "passwort1", Set.of(verwalter.getId()));

        mockMvc.perform(get("/admin/roles").with(as("verwalter")))
                .andExpect(content().string(checkbox("TODO:EDIT:ANY", "disabled")));
        mockMvc.perform(post("/admin/roles/{id}/acl", kind.getId()).with(csrf()).with(as("verwalter"))
                        .param("rules", "TODO:EDIT:ANY"))
                .andExpect(flash().attributeExists("error"));

        assertThat(acl.rulesOf(kind.getId())).isEmpty();
    }
}
