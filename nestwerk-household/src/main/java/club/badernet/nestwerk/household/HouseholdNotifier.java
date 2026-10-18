package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Benachrichtigungen zum Haushaltsplan; Empfänger sind die Benutzer der betroffenen Mitglieder. */
@Component
public class HouseholdNotifier {

    static final String SOURCE = "CHORE_TASK";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN);

    private final NotificationService notifications;
    private final FamilyMemberRepository members;
    private final UserRepository users;
    private final HouseholdAccess access;

    public HouseholdNotifier(NotificationService notifications, FamilyMemberRepository members, UserRepository users,
                             HouseholdAccess access) {
        this.notifications = notifications;
        this.members = members;
        this.users = users;
        this.access = access;
    }

    /** Jemand hat die Aufgabe einem anderen Mitglied gegeben. */
    public void assigned(ChoreTask task, User actor) {
        send(userOf(task.getAssigneeMemberId()).stream().toList(), task, NotificationType.CHORE_ASSIGNED,
                "a" + task.getAssigneeMemberId() + "-" + task.getVersion(),
                actor.getUsername() + " hat dir die Hausarbeit für " + dueLabel(task) + " gegeben.");
    }

    /** Erinnerung zur Fälligkeit an den Zuständigen bzw. – ohne Zuständigen – an alle, die sie nehmen können. */
    public void due(ChoreTask task) {
        send(responsible(task), task, NotificationType.CHORE_DUE, task.getSlotKey(), "Fällig " + dueLabel(task) + ".");
    }

    public void overdue(ChoreTask task) {
        send(responsible(task), task, NotificationType.CHORE_OVERDUE, task.getSlotKey(),
                "War fällig " + dueLabel(task) + " und ist noch offen.");
    }

    private Set<User> responsible(ChoreTask task) {
        Set<User> result = new LinkedHashSet<>();
        if (task.getAssigneeMemberId() != null) {
            userOf(task.getAssigneeMemberId()).ifPresent(result::add);
            return result;
        }
        Collection<FamilyMember> candidates = task.getChore().getMemberIds().isEmpty()
                ? members.findAll() : members.findAllById(task.getChore().getMemberIds());
        for (FamilyMember m : candidates) {
            userOf(m.getId()).ifPresent(result::add);
        }
        return result;
    }

    private Optional<User> userOf(Long memberId) {
        if (memberId == null) {
            return Optional.empty();
        }
        return members.findById(memberId).map(FamilyMember::getUserId).flatMap(users::findById);
    }

    private void send(Collection<User> recipients, ChoreTask task, NotificationType type, String dueKey, String body) {
        for (User u : recipients) {
            if (u.isEnabled() && access.can(u, "VIEW", task)) {
                notifications.notify(u.getId(), type, SOURCE, task.getId(), dueKey, task.getChore().getTitle(), body,
                        "/household/tasks/" + task.getId());
            }
        }
    }

    static String dueLabel(ChoreTask task) {
        LocalDate day = task.getDueOn();
        String label = day.format(DAY);
        return task.getDueTime() == null ? label : label + ", " + task.timeLabel() + " Uhr";
    }
}
