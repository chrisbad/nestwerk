package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class ContactControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired ContactService contactService;
    @Autowired ContactRepository contacts;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        userService.create("leser", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        userService.create("niemand", "passwort1", Set.of());
    }

    private static RequestPostProcessor as(String name) {
        return user(name);
    }

    @Test
    void listsContactsGroupedByCategory() throws Exception {
        contactService.create(new ContactInput("Elektriker Meier", "Handwerker", null, null, null, null, null, null,
                null, null), admin);
        contactService.create(new ContactInput("Grundschule", "Schule", null, null, null, null, null, null, null,
                null), admin);

        mockMvc.perform(get("/contacts").with(as("leser")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Handwerker")))
                .andExpect(content().string(containsString("Elektriker Meier")))
                .andExpect(content().string(not(containsString("href=\"/contacts/new\""))));
    }

    @Test
    void searchFiltersList() throws Exception {
        contactService.create(new ContactInput("Elektriker Meier", null, null, null, null, null, null, null, null,
                null), admin);
        contactService.create(new ContactInput("Grundschule", null, null, null, null, null, null, null, null, null),
                admin);

        mockMvc.perform(get("/contacts").param("q", "meier").with(as("chef")))
                .andExpect(content().string(containsString("Elektriker Meier")))
                .andExpect(content().string(not(containsString("Grundschule"))));
    }

    @Test
    void userWithoutRulesIsForbidden() throws Exception {
        mockMvc.perform(get("/contacts").with(as("niemand"))).andExpect(status().isForbidden());
    }

    @Test
    void createsContactAndShowsDetail() throws Exception {
        mockMvc.perform(post("/contacts").with(csrf()).with(as("chef"))
                        .param("name", "Hausverwaltung").param("phone", "0711 123")
                        .param("url", "https://verwaltung.example"))
                .andExpect(status().is3xxRedirection());

        Contact c = contacts.findAll().stream().filter(x -> x.getName().equals("Hausverwaltung")).findFirst()
                .orElseThrow();
        mockMvc.perform(get("/contacts/{id}", c.getId()).with(as("chef")))
                .andExpect(content().string(containsString("tel:0711123")))
                .andExpect(content().string(containsString("https://verwaltung.example")));
    }

    @Test
    void invalidInputRerendersFormWithError() throws Exception {
        mockMvc.perform(post("/contacts").with(csrf()).with(as("chef"))
                        .param("name", "Kaputt").param("email", "keine-mail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("E-Mail-Adresse ist ungültig")))
                .andExpect(content().string(containsString("value=\"Kaputt\"")));
    }

    @Test
    void readerCannotOpenEditForm() throws Exception {
        Contact c = contactService.create(new ContactInput("Schule", null, null, null, null, null, null, null, null,
                null), admin);

        mockMvc.perform(get("/contacts/{id}", c.getId()).with(as("leser")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/contacts/" + c.getId() + "/edit"))));
        mockMvc.perform(get("/contacts/{id}/edit", c.getId()).with(as("leser")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletesContact() throws Exception {
        Contact c = contactService.create(new ContactInput("Weg", null, null, null, null, null, null, null, null,
                null), admin);

        mockMvc.perform(post("/contacts/{id}/delete", c.getId()).with(csrf()).with(as("chef")))
                .andExpect(status().is3xxRedirection());

        assertThat(contacts.findById(c.getId())).isEmpty();
    }
}
