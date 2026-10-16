package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.update.AvailableUpdate;
import club.badernet.nestwerk.update.Channel;
import club.badernet.nestwerk.update.UpdateCheck;
import club.badernet.nestwerk.update.UpdateCheckTestSupport;
import club.badernet.nestwerk.update.UpdateSettings;
import club.badernet.nestwerk.user.UserRepository;
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
import org.springframework.util.FileSystemUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, UpdateCheckTestSupport.Fakes.class})
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.update.current-version=1.4.0",
        "nestwerk.update.dir=target/update-web-test"})
@AutoConfigureMockMvc
@Transactional
class AdminUpdateControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UpdateCheck check;
    @Autowired UpdateSettings settings;
    @Autowired UpdateCheckTestSupport.Source source;
    @Autowired UserService userService;
    @Autowired UserRepository users;

    @BeforeEach
    void setUp() {
        FileSystemUtils.deleteRecursively(Path.of("target/update-web-test").toFile());
        source.reset();
        check.check();
        users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
        if (users.findByUsername("ohnerecht").isEmpty()) {
            userService.create("ohnerecht", "passwort1", Set.of());
        }
    }

    private static RequestPostProcessor chef() {
        return user("chef").authorities(new SimpleGrantedAuthority("UPDATE_MANAGE"));
    }

    private void offer(String version) {
        source.offer(new AvailableUpdate(version, version, "v" + version, "- **Küche** neu", Instant.now()));
        check.check();
    }

    @Test
    void pageNeedsPermission() throws Exception {
        mockMvc.perform(get("/admin/updates").with(user("ohnerecht"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/updates").with(chef()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1.4.0")))
                .andExpect(content().string(containsString("Updater nicht eingerichtet")))
                .andExpect(content().string(containsString("Updates</a>")));
    }

    @Test
    void showsAvailableVersionWithNotes() throws Exception {
        offer("1.5.0");

        mockMvc.perform(get("/admin/updates").with(chef()))
                .andExpect(content().string(containsString("1.5.0")))
                .andExpect(content().string(containsString("<strong>Küche</strong>")));
    }

    @Test
    void savesChannelAndToken() throws Exception {
        mockMvc.perform(post("/admin/updates/settings").with(csrf()).with(chef())
                        .param("channel", "MAIN").param("token", "ghp_x"))
                .andExpect(redirectedUrl("/admin/updates"))
                .andExpect(flash().attributeExists("success"));

        assertThat(settings.channel()).isEqualTo(Channel.MAIN);
        assertThat(settings.token()).contains("ghp_x");
    }

    @Test
    void startWithoutKnownVersionFails() throws Exception {
        mockMvc.perform(post("/admin/updates/start").with(csrf()).with(chef()))
                .andExpect(redirectedUrl("/admin/updates"))
                .andExpect(flash().attribute("error", "Es ist keine neuere Version bekannt."));
    }

    @Test
    void secondStartIsRejected() throws Exception {
        offer("1.5.0");
        mockMvc.perform(post("/admin/updates/start").with(csrf()).with(chef()))
                .andExpect(flash().attributeExists("success"));

        mockMvc.perform(post("/admin/updates/start").with(csrf()).with(chef()))
                .andExpect(flash().attribute("error", "Es läuft bereits ein Update."));
    }
}
