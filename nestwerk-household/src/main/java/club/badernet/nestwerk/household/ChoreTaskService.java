package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Konkrete Aufgaben: buchen (mit Mithelfern), rückgängig machen, umverteilen und auflisten. */
@Service
public class ChoreTaskService {

    /**
     * Was beim Buchen angegeben wird: Aufwandsgrad, tatsächliche Dauer und Zeitpunkt der Durchführung. Fehlende
     * Werte: mittel, die Minuten des gewählten Grads, jetzt.
     */
    public record Booking(ChoreEffort effort, Integer minutes, LocalDateTime performedAt) {

        public static Booking now() {
            return new Booking(null, null, null);
        }
    }

    /** Geprüfte Buchung. */
    private record CleanBooking(ChoreEffort effort, int minutes, Instant performedAt, LocalDate day, LocalTime time) {
    }

    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    static final Comparator<ChoreTask> ORDER = Comparator.comparing(ChoreTask::getDueOn)
            .thenComparing(t -> t.getDueTime() == null ? LocalTime.MAX : t.getDueTime())
            .thenComparing(t -> t.getChore().getTitle(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ChoreTask::getId);

    private final ChoreTaskRepository tasks;
    private final ChoreService chores;
    private final ChoreCreditRepository credits;
    private final FamilyMemberRepository members;
    private final ChorePlanner planner;
    private final HouseholdAccess access;
    private final HouseholdNotifier notifier;
    private final Clock clock;

    public ChoreTaskService(ChoreTaskRepository tasks, ChoreService chores, ChoreCreditRepository credits,
                            FamilyMemberRepository members, ChorePlanner planner, HouseholdAccess access,
                            HouseholdNotifier notifier, Clock clock) {
        this.tasks = tasks;
        this.chores = chores;
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
     * Bucht die Aufgabe. Erledigt hat sie {@code doerId} (Standard: das eigene Mitglied); jeder Mithelfer bekommt
     * ebenfalls die gebuchten Minuten gutgeschrieben. Die Aufgabe wandert auf den Zeitpunkt der Durchführung.
     */
    @Transactional
    public void complete(Long taskId, User actor, Long doerId, Set<Long> helperIds, Booking booking) {
        ChoreTask task = get(taskId, actor);
        requireOpen(task);
        if (!access.can(actor, "COMPLETE", task)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit abzuhaken");
        }
        finish(task, doer(actor, doerId), helperIds, actor.getId(), validate(task.getChore(), booking));
    }

    /** Buchen ohne Benutzer (Wand-Tablet); die PIN-Prüfung übernimmt der Aufrufer. */
    @Transactional
    public void completeAsMember(Long taskId, Long memberId, Set<Long> helperIds, Booking booking) {
        ChoreTask task = tasks.findById(taskId).orElseThrow(ChoreNotFoundException::new);
        requireOpen(task);
        finish(task, memberId, helperIds, null, validate(task.getChore(), booking));
    }

    /** Darf der Benutzer diese Hausarbeit ungeplant buchen („Jetzt buchen“)? */
    public boolean canBook(User user, Chore chore) {
        return chore.isActive() && access.can(user, "COMPLETE", ChoreTask.adHoc(chore, today(), null));
    }

    /**
     * Bucht eine Hausarbeit ohne geplante Aufgabe, z. B. „bei Bedarf“. Bei „nach Erledigung“ ersetzt die Buchung
     * die offene Aufgabe; der nächste Termin wird ab der Durchführung neu geplant.
     */
    @Transactional
    public ChoreTask bookAdHoc(Long choreId, User actor, Long doerId, Set<Long> helperIds, Booking booking) {
        Chore chore = chores.get(choreId, actor);
        if (!canBook(actor, chore)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit zu buchen");
        }
        Long doer = doer(actor, doerId);
        CleanBooking clean = validate(chore, booking);
        if (chore.getKind() == ChoreKind.INTERVAL) {
            tasks.findAllByChoreIdAndStatus(chore.getId(), ChoreTaskStatus.OPEN).forEach(tasks::delete);
        }
        ChoreTask task = tasks.save(ChoreTask.adHoc(chore, clean.day(), clean.time()));
        finish(task, doer, helperIds, actor.getId(), clean);
        return task;
    }

    /**
     * Plans a chore once for a day (and optionally a time and a member), independent of its rhythm; this is how a
     * chore "on demand" gets a date. Planning for oneself or leaving it open needs the right to book the chore, giving
     * it to someone else the right to assign it.
     */
    @Transactional
    public ChoreTask planOnce(Long choreId, LocalDate day, LocalTime time, Long memberId, User actor) {
        Chore chore = chores.get(choreId, actor);
        if (!canBook(actor, chore)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit einzuplanen");
        }
        if (day == null) {
            throw new IllegalArgumentException("Bitte einen Tag angeben.");
        }
        if (day.isBefore(today())) {
            throw new IllegalArgumentException("Eine Hausarbeit lässt sich nicht für die Vergangenheit einplanen.");
        }
        if (memberId != null && !members.existsById(memberId)) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        Long own = access.memberId(actor).orElse(null);
        if (memberId != null && !memberId.equals(own)) {
            requireMayAssign(chore, memberId, actor);
        }
        ChoreTask task = tasks.save(ChoreTask.scheduledOnce(chore, day, time, memberId));
        if (memberId != null && !memberId.equals(own)) {
            notifier.assigned(task, actor);
        }
        return task;
    }

    private void requireMayAssign(Chore chore, Long memberId, User actor) {
        ChoreTask probe = ChoreTask.adHoc(chore, today(), null);
        if (!access.can(actor, "ASSIGN", probe)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit jemand anderem zuzuweisen");
        }
        if (!access.canAny(actor, "ASSIGN") && !chore.getMemberIds().contains(memberId)) {
            throw new AccessDeniedException("Du kannst sie nur an Zuständige dieser Hausarbeit geben");
        }
    }

    private Long doer(User actor, Long doerId) {
        Long own = access.memberId(actor).orElse(null);
        Long doer = doerId != null ? doerId : own;
        if (doer == null) {
            throw new IllegalArgumentException("Bitte auswählen, welches Mitglied die Hausarbeit erledigt hat.");
        }
        if (!doer.equals(own) && !access.canAny(actor, "COMPLETE")) {
            throw new AccessDeniedException("Du kannst Hausarbeiten nur für dich selbst abhaken");
        }
        return doer;
    }

    private CleanBooking validate(Chore chore, Booking booking) {
        Booking b = booking == null ? Booking.now() : booking;
        ChoreEffort effort = b.effort() != null ? b.effort() : ChoreEffort.MEDIUM;
        int minutes = b.minutes() != null ? b.minutes() : chore.minutesFor(effort);
        if (minutes < 1 || minutes > ChoreService.MAX_MINUTES) {
            throw new IllegalArgumentException("Die Dauer muss zwischen 1 und " + ChoreService.MAX_MINUTES
                    + " Minuten liegen.");
        }
        Instant now = clock.instant();
        Instant performedAt = b.performedAt() == null ? now : b.performedAt().atZone(clock.getZone()).toInstant();
        if (performedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new IllegalArgumentException("Der Zeitpunkt der Erledigung darf nicht in der Zukunft liegen.");
        }
        LocalDateTime local = LocalDateTime.ofInstant(performedAt, clock.getZone());
        return new CleanBooking(effort, minutes, performedAt, local.toLocalDate(),
                local.toLocalTime().truncatedTo(ChronoUnit.MINUTES));
    }

    private void finish(ChoreTask task, Long doer, Set<Long> helperIds, Long userId, CleanBooking booking) {
        Set<Long> people = new LinkedHashSet<>();
        people.add(doer);
        if (helperIds != null) {
            people.addAll(helperIds);
        }
        if (members.findAllById(people).size() != people.size()) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        task.complete(booking.performedAt(), booking.day(), booking.time(), booking.effort(), clock.instant(), userId);
        for (Long member : people) {
            credits.save(new ChoreCredit(task, member, booking.minutes(), booking.effort(), booking.performedAt()));
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
        if (!bookedTodayBy(task, actor) && !access.canAny(actor, "CORRECT")) {
            throw new AccessDeniedException("Keine Berechtigung, diese Erledigung zurückzunehmen");
        }
        credits.deleteAll(credits.findAllByTaskId(taskId));
        if (task.isAdHoc()) {
            // ungeplant gebucht: es gibt keinen Termin, zu dem sie zurückkehren könnte
            Chore chore = task.getChore();
            tasks.delete(task);
            tasks.flush();
            if (chore.getKind() == ChoreKind.INTERVAL) {
                planner.plan(chore, today());
            }
            return;
        }
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
        planner.assignWithTime(task, memberId);
        if (memberId != null && !memberId.equals(previous)
                && !memberId.equals(access.memberId(actor).orElse(null))) {
            notifier.assigned(task, actor);
        }
    }

    /**
     * Plans the open task on another day and time (e.g. moved in the calendar). Needs the right to reassign it;
     * automatic re-planning keeps the time as long as the assignee stays available.
     */
    @Transactional
    public void reschedule(Long taskId, LocalDate day, LocalTime plannedAt, User actor) {
        ChoreTask task = get(taskId, actor);
        requireOpen(task);
        if (day == null || plannedAt == null) {
            throw new IllegalArgumentException("Bitte Tag und Uhrzeit angeben.");
        }
        if (day.isBefore(today())) {
            throw new IllegalArgumentException("Eine Hausarbeit lässt sich nicht in die Vergangenheit verschieben.");
        }
        if (!access.can(actor, "ASSIGN", task)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit zu verschieben");
        }
        task.moveManually(day, plannedAt);
    }

    /**
     * Gives the open task to another member (or nobody) and plans it at the given day and time in one go, e.g. when
     * it is dragged to another person's column in the calendar. Either both changes happen or none.
     */
    @Transactional
    public void reassignAndReschedule(Long taskId, Long memberId, LocalDate day, LocalTime plannedAt, User actor) {
        assign(taskId, memberId, actor);
        reschedule(taskId, day, plannedAt, actor);
    }

    /**
     * Drops the open task for its date only; the chore and its other dates stay. Planning does not create the task
     * again, and for chores due some days after completion the next one is due that many days after this date.
     */
    @Transactional
    public void cancel(Long taskId, User actor) {
        ChoreTask task = get(taskId, actor);
        requireOpen(task);
        if (!access.can(actor, "EDIT", task.getChore())) {
            throw new AccessDeniedException("Keine Berechtigung, diesen Termin der Hausarbeit zu löschen");
        }
        task.cancel();
        tasks.flush();
        if (task.getChore().getKind() == ChoreKind.INTERVAL) {
            planner.plan(task.getChore(), today());
        }
    }

    @Transactional(readOnly = true)
    public List<ChoreTask> between(User viewer, LocalDate from, LocalDate to) {
        return tasks.findAllByDueOnBetweenAndStatusNot(from, to, ChoreTaskStatus.CANCELLED).stream()
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

    /** Hat dieser Benutzer die Aufgabe heute selbst gebucht (dann darf er sie zurücknehmen)? */
    public boolean bookedTodayBy(ChoreTask task, User user) {
        Instant at = task.getBookedAt() != null ? task.getBookedAt() : task.getCompletedAt();
        return user.getId().equals(task.getCompletedByUser()) && at != null
                && at.atZone(clock.getZone()).toLocalDate().equals(today());
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private static void requireOpen(ChoreTask task) {
        if (task.isCancelled()) {
            throw new ChoreConflictException("Dieser Termin der Hausarbeit entfällt.");
        }
        if (!task.isOpen()) {
            throw new ChoreConflictException("Diese Hausarbeit wurde schon erledigt.");
        }
    }
}
