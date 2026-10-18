package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoWatcherTest {

    @Autowired TodoService service;
    @Autowired TodoAccess access;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;

    User admin;
    User mama;
    User papa;
    User oma;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        mama = userService.create("mama", "passwort1", Set.of(benutzer, eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        oma = userService.create("oma", "passwort1", Set.of(benutzer));
    }

    private Todo todo(String title, boolean privat, User owner) {
        return service.create(new TodoInput(title, null, null, null, null, null, null, privat, null, Set.of(),
                Set.of(), null), owner);
    }

    @Test
    void userCanWatchAndUnwatch() {
        Todo t = todo("Rasen", false, mama);

        service.watch(t.getId(), papa);
        assertThat(service.get(t.getId(), papa).getWatcherUserIds()).contains(papa.getId());

        service.unwatch(t.getId(), papa);
        assertThat(service.get(t.getId(), papa).getWatcherUserIds()).doesNotContain(papa.getId());
    }

    @Test
    void addingOthersNeedsManageWatchers() {
        Todo t = todo("Geschenk", true, mama);

        assertThatThrownBy(() -> service.addWatcher(t.getId(), oma.getId(), papa))
                .isInstanceOf(TodoNotFoundException.class);
        Todo own = todo("Papas", false, papa);
        assertThatThrownBy(() -> service.addWatcher(own.getId(), oma.getId(), papa))
                .isInstanceOf(AccessDeniedException.class);

        service.addWatcher(t.getId(), oma.getId(), mama);
        assertThat(access.can(oma, "VIEW", service.get(t.getId(), mama))).isTrue();
    }

    @Test
    void removingOthersNeedsManageWatchersButSelfAlwaysWorks() {
        Todo t = todo("Auto", false, mama);
        service.addWatcher(t.getId(), oma.getId(), mama);

        assertThatThrownBy(() -> service.removeWatcher(t.getId(), oma.getId(), papa))
                .isInstanceOf(AccessDeniedException.class);
        service.removeWatcher(t.getId(), oma.getId(), oma);
        assertThat(service.get(t.getId(), mama).getWatcherUserIds()).isEmpty();
    }

    @Test
    void unknownOrDisabledWatcherIsRejected() {
        Todo t = todo("Auto", false, mama);
        userService.setEnabled(oma.getId(), false);

        assertThatThrownBy(() -> service.addWatcher(t.getId(), 999_999L, mama)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addWatcher(t.getId(), oma.getId(), mama)).isInstanceOf(IllegalArgumentException.class);
    }
}
