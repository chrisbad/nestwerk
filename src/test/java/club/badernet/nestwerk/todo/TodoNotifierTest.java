package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoNotifierTest {

    @Autowired TodoService service;
    @Autowired TodoStructureService structure;
    @Autowired TodoCommentService comments;
    @Autowired NotificationService notifications;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;

    User admin;
    User mama;
    User papa;
    User kind;
    FamilyMember papaMember;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.replaceRules(admin, kinder.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));
        kind = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        papaMember = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
    }

    private Todo todo(String title, boolean privat, AssigneeRef... refs) {
        return service.create(new TodoInput(title, null, null, null, null, null, null, privat, null, Set.of(),
                Set.of(refs), null), mama);
    }

    private List<NotificationType> typesFor(User user) {
        return notifications.list(user.getId(), false, 50).stream().map(Notification::getType).toList();
    }

    @Test
    void assigningNotifiesNewAssigneesButNotTheActor() {
        todo("Reifen", false, AssigneeRef.member(papaMember.getId()));

        assertThat(typesFor(papa)).containsExactly(NotificationType.TODO_ASSIGNED);
        assertThat(typesFor(mama)).isEmpty();
    }

    @Test
    void recipientsMustSeeTheTodo() {
        Role kinder = roles.findByNameIgnoreCase("Kinder").orElseThrow();
        todo("Geheimes Geschenk", true, AssigneeRef.role(kinder.getId()), AssigneeRef.member(papaMember.getId()));

        assertThat(typesFor(kind)).isEmpty();
        assertThat(typesFor(papa)).containsExactly(NotificationType.TODO_ASSIGNED);
    }

    @Test
    void commentNotifiesInvolvedAndMentionReplacesComment() {
        Todo t = todo("Fenster", false, AssigneeRef.member(papaMember.getId()));
        service.addWatcher(t.getId(), kind.getId(), admin);
        notifications.markAllRead(papa.getId());

        comments.add(t.getId(), "Bitte bis Freitag, @kind", mama);

        assertThat(typesFor(papa)).contains(NotificationType.TODO_COMMENT);
        assertThat(typesFor(kind)).containsExactly(NotificationType.TODO_MENTION);
        assertThat(typesFor(mama)).isEmpty();
    }

    @Test
    void actorIsNeverNotified() {
        Todo t = todo("Eigenes", false);
        service.watch(t.getId(), mama);

        comments.add(t.getId(), "Notiz an mich, @mama", mama);
        service.check(t.getId(), mama);

        assertThat(typesFor(mama)).isEmpty();
    }

    @Test
    void closingNotifiesResponsibleAndWatchers() {
        Todo t = todo("Rasen", false, AssigneeRef.member(papaMember.getId()));
        service.addWatcher(t.getId(), kind.getId(), admin);

        service.check(t.getId(), papa);

        assertThat(typesFor(mama)).containsExactly(NotificationType.TODO_STATUS);
        assertThat(typesFor(kind)).containsExactly(NotificationType.TODO_STATUS);
    }

    @Test
    void unblockedMessageHidesTitleOfInvisibleBlocker() {
        Todo geheim = todo("Geheime Vorarbeit", true);
        Todo fliesen = todo("Fliesen", false, AssigneeRef.member(papaMember.getId()));
        structure.link(geheim.getId(), fliesen.getId(), TodoLinkType.BLOCKS, mama);

        service.check(geheim.getId(), mama);

        Notification n = notifications.list(papa.getId(), false, 50).stream()
                .filter(x -> x.getType() == NotificationType.TODO_UNBLOCKED).findFirst().orElseThrow();
        assertThat(n.getBody()).doesNotContain("Geheime Vorarbeit");
    }

    @Test
    void lastBlockerClosedNotifiesUnblocked() {
        Todo estrich = todo("Estrich", false);
        Todo kleber = todo("Kleber kaufen", false);
        Todo fliesen = todo("Fliesen", false, AssigneeRef.member(papaMember.getId()));
        structure.link(estrich.getId(), fliesen.getId(), TodoLinkType.BLOCKS, mama);
        structure.link(kleber.getId(), fliesen.getId(), TodoLinkType.BLOCKS, mama);

        service.check(estrich.getId(), mama);
        assertThat(typesFor(papa)).doesNotContain(NotificationType.TODO_UNBLOCKED);

        service.check(kleber.getId(), mama);
        assertThat(typesFor(papa)).contains(NotificationType.TODO_UNBLOCKED);
    }
}
