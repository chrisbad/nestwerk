package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
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
class SchoolControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;

    FamilyMember lena;

    @BeforeEach
    void setUp() {
        userService.createAdministrator("chef", "passwort1");
        userService.create("mama", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return user("mama").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor editor() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_EDIT"), new SimpleGrantedAuthority("SCHOOL_SETTINGS"));
    }

    @Test
    void pageNeedsTheViewRight() throws Exception {
        mockMvc.perform(get("/school").with(user("mama").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/school").with(viewer())).andExpect(status().isOk());
    }

    @Test
    void editorCreatesProfileAndYear() throws Exception {
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString()).param("state", "BY")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();

        mockMvc.perform(post("/school/profiles/{id}/years", profile.getId())
                        .param("label", "2026/27").param("school", "Grundschule").param("className", "3a")
                        .param("startsOn", "2026-08-01").param("endsOn", "2027-07-31")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        assertThat(school.activeYear(profile.getId()).orElseThrow().getClassName()).isEqualTo("3a");
        mockMvc.perform(get("/school").with(viewer()))
                .andExpect(content().string(containsString("Lena")))
                .andExpect(content().string(containsString("Grundschule, Klasse 3a, Schuljahr 2026/27")));
    }

    @Test
    void viewerCannotChangeAnything() throws Exception {
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString())
                        .with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        assertThat(school.profileOf(lena.getId())).isEmpty();
    }

    @Test
    void invalidInputShowsAnError() throws Exception {
        school.createProfile(lena.getId(), null);
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString())
                        .with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", containsString("schon ein Schulprofil")));
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/school/profiles").param("memberId", lena.getId().toString()).with(editor()))
                .andExpect(status().isForbidden());
    }
}
