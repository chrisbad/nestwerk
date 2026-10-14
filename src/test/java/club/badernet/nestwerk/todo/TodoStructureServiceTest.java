package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclRule;
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
class TodoStructureServiceTest {

    @Autowired TodoStructureService structure;
    @Autowired TodoService service;
    @Autowired TodoRepository todos;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    User admin;
    User mama;
    User papa;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
    }

    private Todo todo(String title, User owner) {
        return service.create(input(title, false), owner);
    }

    private static TodoInput input(String title, boolean privat) {
        return new TodoInput(title, null, null, null, null, null, null, privat, null, Set.of(), Set.of(), null);
    }

    // --- Unteraufgaben ---------------------------------------------------------

    @Test
    void childNeedsLinkRightOnParent() {
        Todo keller = todo("Keller entrümpeln", mama);

        Todo regal = structure.createChild(keller.getId(), input("Regal abbauen", false), mama);
        assertThat(regal.getParentId()).isEqualTo(keller.getId());
        assertThatThrownBy(() -> structure.createChild(keller.getId(), input("Fremd", false), papa))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deepParentCycleIsRejected() {
        Todo a = todo("A", mama);
        Todo b = structure.createChild(a.getId(), input("B", false), mama);
        Todo c = structure.createChild(b.getId(), input("C", false), mama);

        assertThatThrownBy(() -> structure.moveTo(a.getId(), c.getId(), mama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kreis");
        assertThatThrownBy(() -> structure.moveTo(a.getId(), a.getId(), mama))
                .isInstanceOf(IllegalArgumentException.class);
        structure.moveTo(c.getId(), null, mama);
        assertThat(todos.findById(c.getId()).orElseThrow().getParentId()).isNull();
    }

    @Test
    void progressCountsHiddenChildren() {
        Todo parent = todo("Umzug", mama);
        Todo visible = structure.createChild(parent.getId(), input("Kartons", false), mama);
        structure.createChild(parent.getId(), input("Geheim", true), mama);
        service.check(visible.getId(), mama);

        TodoStructureService.Children forPapa = structure.children(parent.getId(), papa);
        assertThat(forPapa.visible()).extracting(Todo::getTitle).containsExactly("Kartons");
        assertThat(forPapa.hiddenCount()).isEqualTo(1);
        assertThat(forPapa.total()).isEqualTo(2);
        assertThat(forPapa.doneCount()).isEqualTo(1);
    }

    @Test
    void deletingParentDeletesChildren() {
        Todo parent = todo("Garten", mama);
        Todo child = structure.createChild(parent.getId(), input("Hecke", false), mama);
        structure.createChild(child.getId(), input("Schere schleifen", false), mama);
        assertThat(structure.descendantCount(parent.getId())).isEqualTo(2);

        service.delete(parent.getId(), mama);
        todos.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM todos WHERE id = ?", Integer.class, child.getId()))
                .isZero();
    }

    @Test
    void ancestorsGiveBreadcrumb() {
        Todo a = todo("Haus", mama);
        Todo b = structure.createChild(a.getId(), input("Bad", false), mama);
        Todo c = structure.createChild(b.getId(), input("Fliesen", false), mama);

        assertThat(structure.ancestors(c.getId(), papa)).extracting(Todo::getTitle).containsExactly("Haus", "Bad");
    }

    // --- Verknüpfungen -----------------------------------------------------------

    @Test
    void blockingIsShownInBothDirections() {
        Todo estrich = todo("Estrich trocknen", mama);
        Todo fliesen = todo("Fliesen legen", mama);

        structure.link(estrich.getId(), fliesen.getId(), TodoLinkType.BLOCKS, mama);

        assertThat(structure.openBlockers(todos.findById(fliesen.getId()).orElseThrow(), mama).count()).isEqualTo(1);
        assertThat(structure.links(fliesen.getId(), mama)).anySatisfy(l -> {
            assertThat(l.label()).isEqualTo("wird blockiert von");
            assertThat(l.other().getTitle()).isEqualTo("Estrich trocknen");
        });
        service.check(estrich.getId(), mama);
        assertThat(structure.openBlockers(todos.findById(fliesen.getId()).orElseThrow(), mama).count()).isZero();
    }

    @Test
    void blockingCycleIsRejected() {
        Todo a = todo("A", mama);
        Todo b = todo("B", mama);
        Todo c = todo("C", mama);
        structure.link(a.getId(), b.getId(), TodoLinkType.BLOCKS, mama);
        structure.link(b.getId(), c.getId(), TodoLinkType.BLOCKS, mama);

        assertThatThrownBy(() -> structure.link(c.getId(), a.getId(), TodoLinkType.BLOCKS, mama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kreis");
    }

    @Test
    void markingDuplicateDiscardsSource() {
        Todo original = todo("Fenster putzen", mama);
        Todo doppelt = todo("Fenster sauber machen", mama);

        structure.link(doppelt.getId(), original.getId(), TodoLinkType.DUPLICATES, mama);

        assertThat(todos.findById(doppelt.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.DISCARDED);
        assertThat(todos.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.OPEN);
    }

    @Test
    void linkingNeedsLinkRightAndVisibleTarget() {
        Todo mine = todo("Meins", mama);
        Todo secret = service.create(input("Geheim", true), papa);

        assertThatThrownBy(() -> structure.link(mine.getId(), secret.getId(), TodoLinkType.RELATES, mama))
                .isInstanceOf(TodoNotFoundException.class);
        assertThatThrownBy(() -> structure.link(mine.getId(), todo("Papas", papa).getId(), TodoLinkType.RELATES, papa))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void hiddenBlockersAreOnlyCounted() {
        Todo secret = service.create(input("Geheim", true), mama);
        Todo open = todo("Offen", mama);
        structure.link(secret.getId(), open.getId(), TodoLinkType.BLOCKS, mama);

        TodoStructureService.Blockers forPapa = structure.openBlockers(todos.findById(open.getId()).orElseThrow(), papa);
        assertThat(forPapa.count()).isEqualTo(1);
        assertThat(forPapa.visibleTitles()).isEmpty();
        assertThat(structure.links(open.getId(), papa)).isEmpty();
    }

    @Test
    void unlinkRemovesLink() {
        Todo a = todo("A", mama);
        Todo b = todo("B", mama);
        structure.link(a.getId(), b.getId(), TodoLinkType.RELATES, mama);
        Long linkId = structure.links(a.getId(), mama).getFirst().linkId();

        structure.unlink(linkId, mama);

        assertThat(structure.links(a.getId(), mama)).isEmpty();
    }

    @Test
    void sameLinkTwiceIsRejected() {
        Todo a = todo("A", mama);
        Todo b = todo("B", mama);
        structure.link(a.getId(), b.getId(), TodoLinkType.RELATES, mama);

        assertThatThrownBy(() -> structure.link(a.getId(), b.getId(), TodoLinkType.RELATES, mama))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
