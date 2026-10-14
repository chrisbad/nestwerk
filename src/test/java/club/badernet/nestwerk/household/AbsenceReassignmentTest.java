package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Ohne Test-Transaktion: die Neuverteilung läuft erst nach dem Speichern des Termins. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class AbsenceReassignmentTest {

    @Autowired ChoreService chores;
    @Autowired ChoreTaskRepository tasks;
    @Autowired CalendarEventService events;
    @Autowired UserService userService;
    @Autowired FamilyMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    Long choreId;
    Long eventId;
    Long userId;
    List<Long> memberIds = List.of();

    @AfterEach
    void cleanUp() {
        if (choreId != null) {
            jdbc.update("DELETE FROM chores WHERE id = ?", choreId);
        }
        if (eventId != null) {
            jdbc.update("DELETE FROM calendar_events WHERE id = ?", eventId);
        }
        memberIds.forEach(id -> jdbc.update("DELETE FROM family_members WHERE id = ?", id));
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void savingAnAbsenceReassignsRotationTasksAfterCommit() {
        User admin = userService.createAdministrator("abwesenheit-chef", "passwort1");
        userId = admin.getId();
        FamilyMember anna = members.save(new FamilyMember("Anna-Test", "#aa0000", null));
        FamilyMember ben = members.save(new FamilyMember("Ben-Test", "#00aa00", null));
        memberIds = List.of(anna.getId(), ben.getId());
        LocalDate today = LocalDate.now(clock);
        Chore chore = chores.create(new ChoreInput("Abwesenheitstest", null, null, 5, ChoreEffort.EASY,
                ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", today, null, null, Distribution.ROTATION,
                List.of(anna.getId(), ben.getId())), admin);
        choreId = chore.getId();
        assertThat(tasks.findAllByChoreId(choreId)).singleElement()
                .satisfies(t -> assertThat(t.getAssigneeMemberId()).isEqualTo(anna.getId()));

        CalendarEvent absence = events.create(new EventInput("Urlaub", null, null, today, null, today, null, true,
                null, false, Set.of(anna.getId()), true), admin);
        eventId = absence.getId();

        assertThat(tasks.findAllByChoreId(choreId)).singleElement()
                .satisfies(t -> assertThat(t.getAssigneeMemberId()).isEqualTo(ben.getId()));
    }
}
