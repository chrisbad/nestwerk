package club.badernet.nestwerk.household;

import club.badernet.nestwerk.item.CalendarItem;
import club.badernet.nestwerk.item.CalendarItemSource;
import club.badernet.nestwerk.item.ItemAction;
import club.badernet.nestwerk.item.ItemType;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chore tasks as calendar items. A task with a planned time and person has a start; others are all-day items of
 * their date. Dropped tasks are not shown. Actions follow the household permissions.
 */
@Component
public class ChoreItems implements CalendarItemSource {

    /** Chores shorter than this would be too small to read and to grab in the calendar. */
    static final int MIN_MINUTES = 5;

    private final ChoreTaskService tasks;
    private final HouseholdAccess access;
    private final FamilyMemberRepository members;

    public ChoreItems(ChoreTaskService tasks, HouseholdAccess access, FamilyMemberRepository members) {
        this.tasks = tasks;
        this.access = access;
        this.members = members;
    }

    @Override
    public ItemType type() {
        return ItemType.CHORE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CalendarItem> items(LocalDate from, LocalDate to, User viewer) {
        if (!access.mayOpen(viewer.getUsername())) {
            return List.of();
        }
        Map<Long, FamilyMember> people = people();
        return tasks.between(viewer, from, to).stream().filter(task -> !task.isCancelled())
                .map(task -> item(task, people, viewer)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CalendarItem> find(Long id, User viewer) {
        if (!access.mayOpen(viewer.getUsername())) {
            return Optional.empty();
        }
        ChoreTask task;
        try {
            task = tasks.get(id, viewer);
        } catch (ChoreNotFoundException e) {
            return Optional.empty();
        }
        return task.isCancelled() ? Optional.empty() : Optional.of(item(task, people(), viewer));
    }

    private Map<Long, FamilyMember> people() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
    }

    private CalendarItem item(ChoreTask task, Map<Long, FamilyMember> people, User viewer) {
        FamilyMember who = task.getAssigneeMemberId() == null ? null : people.get(task.getAssigneeMemberId());
        boolean planned = task.getPlannedAt() != null && who != null;
        int minutes = Math.max(MIN_MINUTES, task.getChore().getMinutesMedium());
        LocalDateTime start = planned ? task.getDueOn().atTime(task.getPlannedAt()) : task.getDueOn().atStartOfDay();
        LocalDateTime end = planned ? start.plusMinutes(minutes) : task.getDueOn().plusDays(1).atStartOfDay();
        String title = task.getChore().getTitle() + " · " + (who == null ? "offen" : who.getName());
        return new CalendarItem.Chore(task.getId(), title, start, end, !planned,
                who == null ? Set.of() : Set.of(who.getId()), who == null ? null : who.getColor(),
                task.getStatus().name(), task.getChore().getId(), actions(task, viewer), minutes, planned);
    }

    private Set<ItemAction> actions(ChoreTask task, User viewer) {
        Set<ItemAction> actions = EnumSet.noneOf(ItemAction.class);
        if (task.isOpen()) {
            if (access.can(viewer, "COMPLETE", task)) {
                actions.add(ItemAction.COMPLETE);
                if (!task.isAdHoc()) {
                    actions.add(ItemAction.SKIP);
                }
            }
            if (access.can(viewer, "ASSIGN", task)) {
                actions.add(ItemAction.MOVE);
            }
            if (access.can(viewer, "EDIT", task.getChore())) {
                actions.add(ItemAction.EDIT);
            }
        } else if (tasks.bookedTodayBy(task, viewer) || access.canAny(viewer, "CORRECT")) {
            actions.add(ItemAction.UNDO);
        }
        return Set.copyOf(actions);
    }
}
