package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoAccessTest {

    @Autowired TodoAccess access;
    @Autowired TodoRepository todos;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired NamedParameterJdbcTemplate jdbc;

    User admin;
    User other;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        other = userService.create("andere", "passwort1", Set.of());
    }

    /** Benutzer mit einer Rolle, die ToDos nur bei den angegebenen Beziehungen sehen darf. */
    private User viewer(String name, String... relations) {
        Role role = roleService.create("Sicht " + name, null, Set.of());
        Set<AclRule> rules = new HashSet<>();
        for (String r : relations) {
            rules.add(new AclRule("TODO", "VIEW", r));
        }
        acl.replaceRules(admin, role.getId(), rules);
        return userService.create(name, "passwort1", Set.of(role.getId()));
    }

    private Todo todo(String title, User owner, boolean privat) {
        Todo t = new Todo(title, owner.getId());
        t.setPrivateTodo(privat);
        return todos.saveAndFlush(t);
    }

    private Set<Long> visibleBySql(User user) {
        TodoAccess.VisibilitySql v = access.visibility(user);
        return new HashSet<>(jdbc.queryForList("SELECT t.id FROM todos t WHERE " + v.sql(), v.params(), Long.class));
    }

    @Test
    void responsibleAndCreatorRelations() {
        User u = viewer("u", "RESPONSIBLE");
        Todo own = todo("Eigenes", u, true);
        Todo foreign = todo("Fremd", other, false);

        assertThat(access.relations(u, own)).contains("RESPONSIBLE", "CREATOR").doesNotContain("ANY");
        assertThat(access.can(u, "VIEW", own)).isTrue();
        assertThat(access.can(u, "VIEW", foreign)).isFalse();
    }

    @Test
    void assignedViaMemberOrRole() {
        User u = viewer("u", "ASSIGNED");
        FamilyMember m = members.saveAndFlush(new FamilyMember("U", "#123456", u.getId()));
        Todo viaMember = todo("Über Mitglied", other, true);
        viaMember.replaceAssignees(Set.of(AssigneeRef.member(m.getId())));
        Todo viaRole = todo("Über Rolle", other, true);
        viaRole.replaceAssignees(Set.of(AssigneeRef.role(u.getRoles().iterator().next().getId())));
        Todo none = todo("Nicht zuständig", other, false);
        todos.flush();

        assertThat(access.can(u, "VIEW", viaMember)).isTrue();
        assertThat(access.can(u, "VIEW", viaRole)).isTrue();
        assertThat(access.can(u, "VIEW", none)).isFalse();
    }

    @Test
    void watchingRelation() {
        User u = viewer("u", "WATCHING");
        Todo watched = todo("Beobachtet", other, true);
        watched.addWatcher(u.getId());
        todos.flush();

        assertThat(access.relations(u, watched)).contains("WATCHING");
        assertThat(access.can(u, "VIEW", watched)).isTrue();
    }

    @Test
    void anyNeverIncludesPrivateTodos() {
        User u = viewer("u", "ANY");
        Todo open = todo("Offen", other, false);
        Todo secret = todo("Geheim", other, true);

        assertThat(access.can(u, "VIEW", open)).isTrue();
        assertThat(access.can(u, "VIEW", secret)).isFalse();
    }

    @Test
    void administratorSeesEverythingAndUserWithoutRulesNothing() {
        Todo secret = todo("Geheim", other, true);
        User nobody = userService.create("niemand", "passwort1", Set.of());

        assertThat(access.can(admin, "VIEW", secret)).isTrue();
        assertThat(access.can(nobody, "VIEW", secret)).isFalse();
        assertThat(access.mayOpen("niemand")).isFalse();
        assertThat(access.mayOpen("chef")).isTrue();
    }

    @Test
    void listConditionMatchesSingleChecks() {
        User any = viewer("alle", "ANY");
        User involved = viewer("beteiligt", "RESPONSIBLE", "ASSIGNED", "WATCHING");
        FamilyMember m = members.saveAndFlush(new FamilyMember("B", "#123456", involved.getId()));
        List<Todo> all = List.of(todo("1", other, false), todo("2", other, true), todo("3", involved, true),
                todo("4", any, false), todo("5", other, true), todo("6", other, true));
        all.get(4).replaceAssignees(Set.of(AssigneeRef.member(m.getId())));
        all.get(5).addWatcher(involved.getId());
        todos.flush();

        for (User u : List.of(any, involved, admin, other)) {
            Set<Long> expected = all.stream().filter(t -> access.can(u, "VIEW", t)).map(Todo::getId)
                    .collect(Collectors.toSet());
            Set<Long> actual = new HashSet<>(visibleBySql(u));
            actual.retainAll(all.stream().map(Todo::getId).collect(Collectors.toSet()));
            assertThat(actual).as(u.getUsername()).isEqualTo(expected);
        }
    }
}
