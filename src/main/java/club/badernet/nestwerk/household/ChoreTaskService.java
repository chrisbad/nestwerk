package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Konkrete Aufgaben: abhaken (mit Mithelfern), rückgängig machen, umverteilen und auflisten. */
@Service
public class ChoreTaskService {

    static final Comparator<ChoreTask> ORDER = Comparator.comparing(ChoreTask::getDueOn)
            .thenComparing(t -> t.getDueTime() == null ? LocalTime.MAX : t.getDueTime())
            .thenComparing(t -> t.getChore().getTitle(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ChoreTask::getId);

    private final ChoreTaskRepository tasks;
    private final ChoreCreditRepository credits;
    private final FamilyMemberRepository members;
    private final ChorePlanner planner;
    private final HouseholdAccess access;
    private final HouseholdNotifier notifier;
    private final Clock clock;

    public ChoreTaskService(ChoreTaskRepository tasks, ChoreCreditRepository credits, FamilyMemberRepository members,
                            ChorePlanner planner, HouseholdAccess access, HouseholdNotifier notifier, Clock clock) {
        this.tasks = tasks;
        this.credits = credits;
        this.members = members;
        this.planner = planner;
        this.access = access;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Liefert die Aufgabe, wenn der Betrachter sie sehen darf; sonst wie „nicht vorhanden“. */
    @Transactional(readOnly = true)
    public ChoreTask get(Long id, User viewer) {
        ChoreTask task = tasks.findById(id).orElseThrow(ChoreNotFoundException::new);
        if (!access.can(viewer, "VIEW", task)) {
            throw new ChoreNotFoundException();
        }
        return task;
    }

    /**
     * Hakt die Aufgabe ab. Erledigt hat sie {@code doerId} (Standard: das eigene Mitglied); jeder Mithelfer
     * bekommt ebenfalls die vollen Minuten gutgeschrieben.
     */
    @Transactional
    public void complete(Long taskId, User actor, Long doerId, Set<Long> helperIds) {
        ChoreTask task = get(taskId, actor);
        requireOpen(task);
        if (!access.can(actor, "COMPLETE", task)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit abzuhaken");
        }
        Long own = access.memberId(actor).orElse(null);
        Long doer = doerId != null ? doerId : own;
        if (doer == null) {
            throw new IllegalArgumentException("Bitte auswählen, welches Mitglied die Hausarbeit erledigt hat.");
        }
        if (!doer.equals(own) && !access.canAny(actor, "COMPLETE")) {
            throw new AccessDeniedException("Du kannst Hausarbeiten nur für dich selbst abhaken");
        }
        finish(task, doer, helperIds, actor.getId());
    }

    /** Abhaken ohne Benutzer (Wand-Tablet); die PIN-Prüfung übernimmt der Aufrufer. */
    @Transactional
    public void completeAsMember(Long taskId, Long memberId, Set<Long> helperIds) {
        ChoreTask task = tasks.findById(taskId).orElseThrow(ChoreNotFoundException::new);
        requireOpen(task);
        finish(task, memberId, helperIds, null);
    }

    private void finish(ChoreTask task, Long doer, Set<Long> helperIds, Long userId) {
        Set<Long> people = new LinkedHashSet<>();
        people.add(doer);
        if (helperIds != null) {
            people.addAll(helperIds);
        }
        if (members.findAllById(people).size() != people.size()) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        Instant now = clock.instant();
        task.complete(now, userId);
        for (Long member : people) {
            credits.save(new ChoreCredit(task, member, now));
        }
        try {
            tasks.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ChoreConflictException("Diese Hausarbeit wurde gerade schon erledigt.");
        }
        if (task.getChore().getKind() == ChoreKind.INTERVAL) {
            planner.plan(task.getChore(), today());
        }
    }

    /** Macht das Abhaken rückgängig: der Abhakende am selben Tag oder wer Gutschriften korrigieren darf. */
    @Transactional
    public void undo(Long taskId, User actor) {
        ChoreTask task = get(taskId, actor);
        if (task.isOpen()) {
            throw new ChoreConflictException("Diese Hausarbeit ist nicht erledigt.");
        }
        boolean ownToday = actor.getId().equals(task.getCompletedByUser())
                && task.getCompletedAt().atZone(clock.getZone()).toLocalDate().equals(today());
        if (!ownToday && !access.canAny(actor, "CORRECT")) {
            throw new AccessDeniedException("Keine Berechtigung, diese Erledigung zurückzunehmen");
        }
        credits.deleteAll(credits.findAllByTaskId(taskId));
        task.reopen();
        if (task.getChore().getKind() == ChoreKind.INTERVAL) {
            tasks.findAllByChoreIdAndStatus(task.getChore().getId(), ChoreTaskStatus.OPEN).stream()
                    .filter(t -> !t.getId().equals(taskId))
                    .forEach(tasks::delete);
        }
        tasks.flush();
    }

    /** Zuständigen ändern; ohne Recht für alle nur an Kandidaten der Hausarbeit oder an sich selbst. */
    @Transactional
    public void assign(Long taskId, Long memberId, User actor) {
        ChoreTask task = get(taskId, actor);
        requireOpen(task);
        if (memberId != null && !members.existsById(memberId)) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        if (!access.can(actor, "ASSIGN", task)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit umzuverteilen");
        }
        if (!access.canAny(actor, "ASSIGN")) {
            Optional<Long> own = access.memberId(actor);
            boolean allowed = memberId != null
                    && (task.getChore().getMemberIds().contains(memberId) || own.map(memberId::equals).orElse(false));
            if (!allowed) {
                throw new AccessDeniedException("Du kannst nur an andere Zuständige dieser Hausarbeit abgeben");
            }
        }
        Long previous = task.getAssigneeMemberId();
        task.assign(memberId);
        if (memberId != null && !memberId.equals(previous)
                && !memberId.equals(access.memberId(actor).orElse(null))) {
            notifier.assigned(task, actor);
        }
    }

    @Transactional(readOnly = true)
    public List<ChoreTask> between(User viewer, LocalDate from, LocalDate to) {
        return tasks.findAllByDueOnBetween(from, to).stream()
                .filter(t -> access.can(viewer, "VIEW", t))
                .sorted(ORDER).toList();
    }

    @Transactional(readOnly = true)
    public List<ChoreTask> overdue(User viewer, LocalDate today) {
        return tasks.findAllByStatusAndDueOnBefore(ChoreTaskStatus.OPEN, today).stream()
                .filter(t -> access.can(viewer, "VIEW", t))
                .sorted(ORDER).toList();
    }

    /** Eigene offene Aufgaben bis heute (auch überfällige) und offene zum Nehmen. */
    @Transactional(readOnly = true)
    public List<ChoreTask> mine(User viewer, LocalDate today) {
        Optional<Long> own = access.memberId(viewer);
        if (own.isEmpty()) {
            return List.of();
        }
        return tasks.findAllByStatusAndDueOnLessThanEqual(ChoreTaskStatus.OPEN, today).stream()
                .filter(t -> HouseholdAccess.isResponsible(own.get(), t))
                .filter(t -> access.can(viewer, "VIEW", t))
                .sorted(ORDER).toList();
    }

    @Transactional(readOnly = true)
    public List<ChoreCredit> credits(Long taskId) {
        return credits.findAllByTaskId(taskId);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private static void requireOpen(ChoreTask task) {
        if (!task.isOpen()) {
            throw new ChoreConflictException("Diese Hausarbeit wurde schon erledigt.");
        }
    }
}
