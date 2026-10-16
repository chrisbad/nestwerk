package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.calendar.CalendarPermission;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Blocker und Ruhe-Ziele anlegen und pflegen. Rechte wie bei Terminen: anlegen mit {@code CALENDAR_EVENT_CREATE},
 * eigene (selbst angelegt oder für die eigene Person) ändern mit {@code _EDIT_OWN}, alle mit {@code _EDIT_ALL}.
 */
@Service
public class BlockerService {

    private static final int MAX_TITLE = 100;

    private final BlockerRepository blockers;
    private final RestGoalRepository goals;
    private final FamilyMemberRepository members;
    private final PermissionCatalog permissions;
    private final ApplicationEventPublisher publisher;

    public BlockerService(BlockerRepository blockers, RestGoalRepository goals, FamilyMemberRepository members,
                          PermissionCatalog permissions, ApplicationEventPublisher publisher) {
        this.blockers = blockers;
        this.goals = goals;
        this.members = members;
        this.permissions = permissions;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public List<Blocker> all() {
        return blockers.findAllByOrderByTitle();
    }

    @Transactional(readOnly = true)
    public List<Blocker> forMember(Long memberId) {
        return blockers.findAllByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public Blocker get(Long id) {
        return blockers.findById(id).orElseThrow(() -> new IllegalArgumentException("Den Blocker gibt es nicht."));
    }

    @Transactional
    public Blocker create(BlockerInput in, User actor) {
        require(actor, CalendarPermission.CALENDAR_EVENT_CREATE);
        validate(in);
        Blocker blocker = new Blocker(title(in), in.kind(), in.startTime(), in.endTime(), in.weekdays(),
                in.validFrom(), in.validUntil(), in.strength(), in.visibility(), in.quiet(), in.holidaysOff(),
                actor.getId());
        applySets(blocker, in);
        Blocker saved = blockers.save(blocker);
        publisher.publishEvent(new BlockersChangedEvent());
        return saved;
    }

    @Transactional
    public void update(Long id, BlockerInput in, User actor) {
        Blocker blocker = get(id);
        requireEdit(blocker, actor);
        validate(in);
        blocker.update(title(in), in.kind(), in.startTime(), in.endTime(), in.weekdays(), in.validFrom(),
                in.validUntil(), in.strength(), in.visibility(), in.quiet(), in.holidaysOff());
        applySets(blocker, in);
        publisher.publishEvent(new BlockersChangedEvent());
    }

    @Transactional
    public void delete(Long id, User actor) {
        Blocker blocker = get(id);
        requireEdit(blocker, actor);
        blockers.delete(blocker);
        publisher.publishEvent(new BlockersChangedEvent());
    }

    public boolean canCreate(User actor) {
        return has(actor, CalendarPermission.CALENDAR_EVENT_CREATE);
    }

    public boolean canEdit(Blocker blocker, User actor) {
        if (has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_ALL)) {
            return true;
        }
        if (!has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_OWN)) {
            return false;
        }
        return actor.getId().equals(blocker.getCreatedBy())
                || ownMember(actor).map(m -> blocker.getMemberIds().contains(m)).orElse(false);
    }

    /** Ob der Nutzer Ruhe-Ziele dieser Person pflegen darf: die eigene Person oder mit {@code _EDIT_ALL}. */
    public boolean canEditGoals(Long memberId, User actor) {
        return has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_ALL)
                || ownMember(actor).filter(memberId::equals).isPresent();
    }

    public Optional<Long> ownMember(User actor) {
        return members.findByUserId(actor.getId()).map(FamilyMember::getId);
    }

    // --- Ruhe-Ziele ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RestGoal> goals(Long memberId) {
        return goals.findAllByMemberId(memberId);
    }

    @Transactional
    public RestGoal saveGoal(Long id, Long memberId, RestGoal.Kind kind, LocalTime windowStart, LocalTime windowEnd,
                             int target, String label, User actor) {
        if (!canEditGoals(memberId, actor)) {
            throw new AccessDeniedException("Keine Berechtigung für die Ruhe-Ziele dieser Person");
        }
        if (kind == RestGoal.Kind.WINDOWS && (windowStart == null || windowEnd == null
                || !windowEnd.isAfter(windowStart))) {
            throw new IllegalArgumentException("Bitte ein Zeitfenster angeben, das nach dem Beginn endet.");
        }
        if (target < 1 || target > (kind == RestGoal.Kind.WINDOWS ? 7 : 7 * 24 * 60)) {
            throw new IllegalArgumentException("Das Ziel liegt außerhalb des Möglichen.");
        }
        String clean = label == null || label.isBlank()
                ? (kind == RestGoal.Kind.WINDOWS ? "Freie Zeitfenster" : "Ruhezeit") : label.trim();
        if (id == null) {
            return goals.save(new RestGoal(memberId, kind, windowStart, windowEnd, target, clip(clean)));
        }
        RestGoal goal = goals.findById(id).filter(g -> g.getMemberId().equals(memberId))
                .orElseThrow(() -> new IllegalArgumentException("Das Ziel gibt es nicht."));
        goal.update(kind, windowStart, windowEnd, target, clip(clean));
        return goal;
    }

    @Transactional
    public void deleteGoal(Long id, User actor) {
        RestGoal goal = goals.findById(id).orElseThrow(() -> new IllegalArgumentException("Das Ziel gibt es nicht."));
        if (!canEditGoals(goal.getMemberId(), actor)) {
            throw new AccessDeniedException("Keine Berechtigung für die Ruhe-Ziele dieser Person");
        }
        goals.delete(goal);
    }

    // --- Hilfen ------------------------------------------------------------------------

    private void validate(BlockerInput in) {
        if (in.kind() == null || in.strength() == null || in.visibility() == null) {
            throw new IllegalArgumentException("Bitte Art, Stärke und Sichtbarkeit wählen.");
        }
        if (in.startTime() == null || in.endTime() == null || in.startTime().equals(in.endTime())) {
            throw new IllegalArgumentException("Beginn und Ende müssen verschieden sein.");
        }
        if (in.weekdays() == null || in.weekdays().isEmpty()) {
            throw new IllegalArgumentException("Bitte mindestens einen Wochentag wählen.");
        }
        if (in.validFrom() == null) {
            throw new IllegalArgumentException("Bitte angeben, ab wann der Blocker gilt.");
        }
        if (in.validUntil() != null && in.validUntil().isBefore(in.validFrom())) {
            throw new IllegalArgumentException("„Gilt bis“ liegt vor „gilt ab“.");
        }
        if (in.memberIds() == null || in.memberIds().isEmpty()) {
            throw new IllegalArgumentException("Bitte mindestens eine Person wählen.");
        }
        Set<Long> all = new HashSet<>(in.memberIds());
        if (in.plannerIds() != null) {
            all.addAll(in.plannerIds());
        }
        if (members.findAllById(all).size() != all.size()) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
    }

    private static String title(BlockerInput in) {
        return in.title() == null || in.title().isBlank() ? in.kind().label() : clip(in.title().trim());
    }

    private static String clip(String value) {
        return value.length() > MAX_TITLE ? value.substring(0, MAX_TITLE) : value;
    }

    private static void applySets(Blocker blocker, BlockerInput in) {
        blocker.setMemberIds(in.memberIds());
        blocker.setPlannerIds(in.plannerIds() == null ? Set.of() : in.plannerIds());
        blocker.setSkips(in.skips() == null ? Set.of() : in.skips());
    }

    private void requireEdit(Blocker blocker, User actor) {
        if (!canEdit(blocker, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Blockers");
        }
    }

    private boolean has(User user, CalendarPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    private void require(User user, CalendarPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung: " + permission.name());
        }
    }
}
