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
class TodoCommentServiceTest {

    @Autowired TodoCommentService comments;
    @Autowired TodoService service;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;

    User admin;
    User mama;
    User papa;
    User leser;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        Role nurLesen = roleService.create("Nur lesen", null, Set.of());
        acl.replaceRules(admin, nurLesen.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));
        leser = userService.create("leser", "passwort1", Set.of(nurLesen.getId()));
    }

    private Todo todo(String title, boolean privat) {
        return service.create(new TodoInput(title, null, null, null, null, null, null, privat, null, Set.of(),
                Set.of(), null), mama);
    }

    @Test
    void addsCommentsInOrder() {
        Todo t = todo("Fenster", false);

        comments.add(t.getId(), "  Erster  ", papa);
        comments.add(t.getId(), "Zweiter", mama);

        assertThat(comments.forTodo(t.getId(), mama)).extracting(TodoComment::getBody)
                .containsExactly("Erster", "Zweiter");
    }

    @Test
    void commentingNeedsCommentRight() {
        Todo t = todo("Fenster", false);

        assertThatThrownBy(() -> comments.add(t.getId(), "Darf ich?", leser)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsEmptyAndOverlongComments() {
        Todo t = todo("Fenster", false);

        assertThatThrownBy(() -> comments.add(t.getId(), "  ", mama)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comments.add(t.getId(), "x".repeat(10_001), mama))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAuthorEditsAndAuthorOrAdminDeletes() {
        Todo t = todo("Fenster", false);
        TodoComment c = comments.add(t.getId(), "Original", papa);

        assertThatThrownBy(() -> comments.edit(c.getId(), "Fremd geändert", mama))
                .isInstanceOf(AccessDeniedException.class);
        comments.edit(c.getId(), "Geändert", papa);
        assertThat(comments.forTodo(t.getId(), papa).getFirst().getBody()).isEqualTo("Geändert");
        assertThat(comments.forTodo(t.getId(), papa).getFirst().getEditedAt()).isNotNull();

        assertThatThrownBy(() -> comments.delete(c.getId(), mama)).isInstanceOf(AccessDeniedException.class);
        comments.delete(c.getId(), admin);
        assertThat(comments.forTodo(t.getId(), papa)).isEmpty();
    }

    @Test
    void commentsOfInvisibleTodoAreNotFound() {
        Todo secret = todo("Geheim", true);
        comments.add(secret.getId(), "Psst", mama);

        assertThatThrownBy(() -> comments.forTodo(secret.getId(), papa)).isInstanceOf(TodoNotFoundException.class);
    }

    @Test
    void mentionsOnlyUsersWhoMaySeeTheTodo() {
        Todo open = todo("Offen", false);
        Todo secret = todo("Geheim", true);

        assertThat(comments.mentioned("Hallo @papa und @Leser, siehe mail@example.org", open))
                .extracting(User::getUsername).containsExactlyInAnyOrder("papa", "leser");
        assertThat(comments.mentioned("@papa schau mal", secret)).isEmpty();
        assertThat(comments.mentioned("@unbekannt", open)).isEmpty();
    }
}
