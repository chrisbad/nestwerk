package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoRepositoryTest {

    @Autowired TodoRepository todos;
    @Autowired ContactRepository contacts;
    @Autowired FamilyMemberRepository members;
    @Autowired RoleRepository roles;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void storesTodoWithTagsAndMixedAssignees() {
        User owner = userService.create("besitzer", "passwort1", Set.of());
        FamilyMember lena = members.save(new FamilyMember("Lena", "#aa3366", null));
        Contact meier = contacts.save(new Contact("Elektriker Meier", owner.getId()));
        Long eltern = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();

        Todo todo = new Todo("Steckdose Bad", owner.getId());
        todo.setPriority(TodoPriority.HIGH);
        todo.setDueOn(LocalDate.of(2026, 11, 1));
        todo.setTags(Set.of("haus", "strom"));
        todo.replaceAssignees(Set.of(AssigneeRef.member(lena.getId()), AssigneeRef.role(eltern),
                AssigneeRef.contact(meier.getId())));
        Todo saved = todos.saveAndFlush(todo);

        Todo loaded = todos.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getTags()).containsExactlyInAnyOrder("haus", "strom");
        assertThat(loaded.assigneeRefs()).containsExactlyInAnyOrder(AssigneeRef.member(lena.getId()),
                AssigneeRef.role(eltern), AssigneeRef.contact(meier.getId()));
        assertThat(loaded.getStatus()).isEqualTo(TodoStatus.OPEN);
        assertThat(loaded.getResponsibleUserId()).isEqualTo(owner.getId());
    }

    @Test
    void assigneeMustReferenceExactlyOneTarget() {
        User owner = userService.create("besitzer", "passwort1", Set.of());
        Todo todo = todos.saveAndFlush(new Todo("Kaputt", owner.getId()));
        FamilyMember lena = members.saveAndFlush(new FamilyMember("Lena", "#aa3366", null));
        Long role = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO todo_assignees (todo_id, member_id, role_id) VALUES (?, ?, ?)",
                todo.getId(), lena.getId(), role))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
