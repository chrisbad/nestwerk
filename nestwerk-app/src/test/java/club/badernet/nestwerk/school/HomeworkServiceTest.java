package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoStatus;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class HomeworkServiceTest {

    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired HomeworkService homework;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User guest;
    FamilyMember lena;
    SchoolYear year;
    SchoolSubject german;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        guest = users.create("gast", "passwort1", Set.of());
        lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
        german = timetable.addSubject(year.getId(), "Deutsch", null, admin);
    }

    @Test
    void createsATodoAssignedToTheChildWithSubjectTag() {
        Todo todo = homework.create(year.getId(), german.getId(), "Seite 12 lesen", "Bis zum Absatz 3",
                LocalDate.of(2026, 9, 10), null, admin);

        assertThat(todo.getDueOn()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(todo.getTags()).contains("hausaufgabe", "deutsch");
        assertThat(todo.assigneeRefs()).containsExactly(AssigneeRef.member(lena.getId()));
        assertThat(homework.list(year.getId(), null, false, admin)).extracting(i -> i.todo().getId())
                .containsExactly(todo.getId());
        assertThat(homework.list(year.getId(), null, false, admin).getFirst().subject().getName())
                .isEqualTo("Deutsch");
    }

    @Test
    void dueDateAndTitleAreRequired() {
        assertThatThrownBy(() -> homework.create(year.getId(), null, "x", null, null, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fällig");
        assertThatThrownBy(() -> homework.create(year.getId(), null, " ", null, LocalDate.of(2026, 9, 1), null, admin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsOpenSortedByDueDateAndFiltersBySubject() {
        SchoolSubject math = timetable.addSubject(year.getId(), "Mathe", null, admin);
        homework.create(year.getId(), german.getId(), "B", null, LocalDate.of(2026, 9, 12), null, admin);
        homework.create(year.getId(), math.getId(), "A", null, LocalDate.of(2026, 9, 10), null, admin);
        homework.create(year.getId(), null, "C", null, LocalDate.of(2026, 9, 11), null, admin);

        assertThat(homework.list(year.getId(), null, false, admin)).extracting(i -> i.todo().getTitle())
                .containsExactly("A", "C", "B");
        assertThat(homework.list(year.getId(), math.getId(), false, admin)).extracting(i -> i.todo().getTitle())
                .containsExactly("A");
    }

    @Test
    void doneMovesBetweenOpenAndDoneLists() {
        Todo todo = homework.create(year.getId(), german.getId(), "Lesen", null, LocalDate.of(2026, 9, 10), null,
                admin);

        homework.setDone(todo.getId(), true, admin);

        assertThat(homework.list(year.getId(), null, false, admin)).isEmpty();
        assertThat(homework.list(year.getId(), null, true, admin)).extracting(i -> i.todo().getStatus())
                .containsExactly(TodoStatus.DONE);

        homework.setDone(todo.getId(), false, admin);
        assertThat(homework.list(year.getId(), null, false, admin)).hasSize(1);
    }

    @Test
    void recurringHomeworkShowsTheNextOccurrenceAfterCompletion() {
        Todo first = homework.create(year.getId(), german.getId(), "Vokabeln", null, LocalDate.of(2026, 9, 7),
                "FREQ=WEEKLY", admin);

        homework.setDone(first.getId(), true, admin);

        List<HomeworkService.Item> open = homework.list(year.getId(), null, false, admin);
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().todo().getId()).isNotEqualTo(first.getId());
        assertThat(open.getFirst().todo().getDueOn()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(homework.list(year.getId(), null, true, admin)).isEmpty();

        homework.setDone(open.getFirst().todo().getId(), true, admin);
        assertThat(homework.list(year.getId(), null, false, admin).getFirst().todo().getDueOn())
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void needsTheHomeworkRightAndOnlyAcceptsHomework() {
        assertThatThrownBy(() -> homework.create(year.getId(), null, "x", null, LocalDate.of(2026, 9, 1), null, guest))
                .isInstanceOf(AccessDeniedException.class);

        Todo plain = homework.create(year.getId(), null, "Echt", null, LocalDate.of(2026, 9, 1), null, admin);
        assertThatThrownBy(() -> homework.setDone(plain.getId(), true, guest))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> homework.setDone(999_999L, true, admin)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void todosOfOtherYearsStayApart() {
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();
        homework.create(year.getId(), null, "Altes Jahr", null, LocalDate.of(2026, 9, 1), null, admin);
        SchoolYear next = school.startYear(profile.getId(), "2027/28", null, "4a", LocalDate.of(2027, 9, 1),
                LocalDate.of(2028, 7, 31));

        assertThat(homework.list(next.getId(), null, false, admin)).isEmpty();
        assertThat(homework.list(year.getId(), null, false, admin)).hasSize(1);
    }
}
