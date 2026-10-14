package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
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

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoServiceTest {

    @Autowired TodoService service;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired ContactRepository contacts;
    @Autowired TodoRepository todos;

    User admin;
    User mama;
    User papa;
    User kid;
    FamilyMember mamaMember;
    FamilyMember papaMember;
    FamilyMember kidMember;
    Role eltern;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role kind = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kind.getId(), "KIND");
        mama = userService.create("mama", "passwort1", Set.of(benutzer, eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(benutzer, eltern.getId()));
        kid = userService.create("kind", "passwort1", Set.of(kind.getId()));
        mamaMember = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        papaMember = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
        kidMember = members.save(new FamilyMember("Kind", "#0000aa", kid.getId()));
    }

    private static TodoInput input(String title) {
        return new TodoInput(title, null, null, null, null, null, null, false, null, Set.of(), Set.of(), null);
    }

    private static TodoInput assigned(String title, CompletionMode mode, AssigneeRef... refs) {
        return new TodoInput(title, null, null, null, null, null, null, false, mode, Set.of(), Set.of(refs), null);
    }

    @Test
    void createsTodoWithCleanedTagsAndDefaults() {
        Todo t = service.create(new TodoInput("  Keller entrümpeln ", "Alles raus", null, null,
                LocalDate.of(2026, 11, 30), null, null, false, null, Set.of(" Haus ", "", "HAUS", "Keller"),
                Set.of(), null), mama);

        assertThat(t.getTitle()).isEqualTo("Keller entrümpeln");
        assertThat(t.getPriority()).isEqualTo(TodoPriority.NORMAL);
        assertThat(t.getCompletionMode()).isEqualTo(CompletionMode.ANY);
        assertThat(t.getTags()).containsExactlyInAnyOrder("haus", "keller");
        assertThat(t.getResponsibleUserId()).isEqualTo(mama.getId());
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> service.create(input(" "), mama)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new TodoInput("X", null, null, null, null, null, 400, false, null,
                Set.of(), Set.of(), null), mama)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new TodoInput("X", null, null, null, null, null, null, false, null,
                Set.of("x".repeat(41)), Set.of(), null), mama)).hasMessageContaining("Schlagwort");
        assertThatThrownBy(() -> service.create(assigned("X", null, AssigneeRef.member(999_999L)), mama))
                .hasMessageContaining("Zuständig");
    }

    @Test
    void availableAfterDueIsRejected() {
        assertThatThrownBy(() -> service.create(new TodoInput("X", null, null, LocalDate.of(2026, 12, 2),
                LocalDate.of(2026, 12, 1), null, null, false, null, Set.of(), Set.of(), null), mama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("„ab“");
    }

    @Test
    void privateTodoNeedsItsOwnPermission() {
        TodoInput secret = new TodoInput("Geschenk", null, null, null, null, null, null, true, null, Set.of(),
                Set.of(), null);

        assertThatThrownBy(() -> service.create(secret, kid)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.create(secret, mama).isPrivateTodo()).isTrue();
    }

    @Test
    void foreignTodoWithoutViewRightLooksLikeNotFound() {
        Todo t = service.create(input("Steuer"), mama);

        assertThatThrownBy(() -> service.get(t.getId(), kid)).isInstanceOf(TodoNotFoundException.class);
    }

    @Test
    void editingNeedsEditAndAssigningNeedsAssign() {
        Todo t = service.create(assigned("Zimmer aufräumen", CompletionMode.ANY, AssigneeRef.member(kidMember.getId())),
                mama);

        TodoInput renamed = new TodoInput("Zimmer sofort aufräumen", null, null, null, null, null, null, false,
                CompletionMode.ANY, Set.of(), Set.of(AssigneeRef.member(kidMember.getId())), mama.getId());
        assertThatThrownBy(() -> service.update(t.getId(), renamed, t.getVersion(), kid))
                .isInstanceOf(AccessDeniedException.class);

        TodoInput reassigned = new TodoInput("Zimmer aufräumen", null, null, null, null, null, null, false,
                CompletionMode.ANY, Set.of(), Set.of(AssigneeRef.member(papaMember.getId())), mama.getId());
        service.update(t.getId(), reassigned, t.getVersion(), papa);
        assertThat(service.get(t.getId(), mama).assigneeRefs()).containsExactly(AssigneeRef.member(papaMember.getId()));
    }

    @Test
    void changingResponsibleNeedsItsOwnPermission() {
        Todo t = service.create(input("Auto"), mama);
        TodoInput handOver = new TodoInput("Auto", null, null, null, null, null, null, false, null, Set.of(),
                Set.of(), papa.getId());

        assertThatThrownBy(() -> service.update(t.getId(), handOver, t.getVersion(), mama))
                .isInstanceOf(AccessDeniedException.class);
        service.update(t.getId(), handOver, t.getVersion(), admin);
        assertThat(service.get(t.getId(), admin).getResponsibleUserId()).isEqualTo(papa.getId());
    }

    @Test
    void staleVersionIsAConflict() {
        Todo t = service.create(input("Fenster"), mama);
        int stale = t.getVersion();
        service.update(t.getId(), input("Fenster putzen"), stale, mama);
        todos.flush();

        assertThatThrownBy(() -> service.update(t.getId(), input("Fenster streichen"), stale, mama))
                .isInstanceOf(TodoConflictException.class)
                .hasMessageContaining("mama");
    }

    @Test
    void checkingWithAnyModeClosesTodo() {
        Todo t = service.create(assigned("Müll", CompletionMode.ANY, AssigneeRef.member(kidMember.getId())), mama);

        service.check(t.getId(), kid);

        Todo done = service.get(t.getId(), mama);
        assertThat(done.getStatus()).isEqualTo(TodoStatus.DONE);
        assertThat(done.getCompletedBy()).isEqualTo(kid.getId());
        service.uncheck(t.getId(), kid);
        assertThat(service.get(t.getId(), mama).getStatus()).isEqualTo(TodoStatus.OPEN);
    }

    @Test
    void eachModeNeedsEveryAssigneeIncludingRoleMembers() {
        Todo t = service.create(assigned("Impfpass prüfen", CompletionMode.EACH, AssigneeRef.role(eltern.getId()),
                AssigneeRef.member(kidMember.getId())), mama);

        assertThat(t.getCompletions()).hasSize(3);
        service.check(t.getId(), mama);
        service.check(t.getId(), kid);
        Todo partly = service.get(t.getId(), mama);
        assertThat(partly.getStatus()).isEqualTo(TodoStatus.OPEN);
        assertThat(partly.completedCount()).isEqualTo(2);

        service.check(t.getId(), papa);
        assertThat(service.get(t.getId(), mama).getStatus()).isEqualTo(TodoStatus.DONE);
    }

    @Test
    void eachModeContactIsCheckedByResponsible() {
        Contact meier = contacts.save(new Contact("Meier", mama.getId()));
        Todo t = service.create(assigned("Angebot", CompletionMode.EACH, AssigneeRef.contact(meier.getId())), mama);

        service.check(t.getId(), mama);

        assertThat(service.get(t.getId(), mama).getStatus()).isEqualTo(TodoStatus.DONE);
    }

    @Test
    void eachModeWithoutResolvableMembersFallsBackToResponsible() {
        Role leer = roleService.create("Ohne Mitglieder", null, Set.of());
        Todo t = service.create(assigned("Irgendwas", CompletionMode.EACH, AssigneeRef.role(leer.getId())), mama);

        assertThat(t.getCompletions()).isEmpty();
        service.check(t.getId(), mama);
        assertThat(service.get(t.getId(), mama).getStatus()).isEqualTo(TodoStatus.DONE);
    }

    @Test
    void notAssignedPersonCannotCheckEachTodo() {
        Todo t = service.create(assigned("Zimmer", CompletionMode.EACH, AssigneeRef.member(kidMember.getId())), mama);

        assertThatThrownBy(() -> service.check(t.getId(), papa)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void takingSetsInProgressAndStatusChangeNeedsPermission() {
        Todo t = service.create(input("Rasen"), mama);

        service.take(t.getId(), papa);
        assertThat(service.get(t.getId(), mama).getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);

        Todo other = service.create(input("Fremd"), mama);
        assertThatThrownBy(() -> service.changeStatus(other.getId(), TodoStatus.DISCARDED, kid))
                .isInstanceOf(TodoNotFoundException.class);
    }

    @Test
    void deletingNeedsDeletePermission() {
        Todo t = service.create(input("Weg"), mama);

        assertThatThrownBy(() -> service.delete(t.getId(), papa)).isInstanceOf(AccessDeniedException.class);
        service.delete(t.getId(), mama);
        assertThatThrownBy(() -> service.get(t.getId(), mama)).isInstanceOf(TodoNotFoundException.class);
    }
}
