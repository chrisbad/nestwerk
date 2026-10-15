package club.badernet.nestwerk.household;

import jakarta.persistence.EntityManager;
import net.fortuna.ical4j.model.Recur;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Legt die konkreten Aufgaben einer Hausarbeit im Planungshorizont an und verteilt sie. Die Planung ist
 * idempotent: je Hausarbeit und Termin ({@code slot_key}) gibt es höchstens eine Aufgabe.
 */
@Component
public class ChorePlanner {

    public static final int HORIZON_DAYS = 21;
    static final int BALANCE_DAYS = 28;

    private record Slot(LocalDate day, LocalTime time) {
        String key() {
            return ChoreTask.slotKey(day, time);
        }
    }

    private final ChoreRepository chores;
    private final ChoreTaskRepository tasks;
    private final ChoreDateRepository dates;
    private final Absences absences;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public ChorePlanner(ChoreRepository chores, ChoreTaskRepository tasks, ChoreDateRepository dates,
                        Absences absences, NamedParameterJdbcTemplate jdbc, EntityManager entityManager, Clock clock) {
        this.chores = chores;
        this.tasks = tasks;
        this.dates = dates;
        this.absences = absences;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Plant alle aktiven Hausarbeiten und verteilt Aufgaben Abwesender neu (täglich und nach Kalenderänderungen). */
    @Transactional
    public void planAll(LocalDate today) {
        for (Chore chore : chores.findAllByActiveTrue()) {
            plan(chore, today);
        }
        reassignAbsent(today);
    }

    /** Verwirft die künftigen offenen Aufgaben und plant neu (nach Änderungen an der Hausarbeit). */
    @Transactional
    public void replan(Chore chore, LocalDate today) {
        removeFutureOpen(chore, today);
        plan(chore, today);
    }

    @Transactional
    public void removeFutureOpen(Chore chore, LocalDate today) {
        tasks.findAllByChoreIdAndStatus(chore.getId(), ChoreTaskStatus.OPEN).stream()
                .filter(t -> !t.getDueOn().isBefore(today))
                .forEach(tasks::delete);
        tasks.flush();
    }

    @Transactional
    public void plan(Chore chore, LocalDate today) {
        if (!chore.isActive()) {
            return;
        }
        Map<String, ChoreTask> existing = tasks.findAllByChoreId(chore.getId()).stream()
                .collect(Collectors.toMap(ChoreTask::getSlotKey, Function.identity()));
        List<Slot> slots = slots(chore, today, existing);
        if (slots.isEmpty()) {
            return;
        }
        Absences.AbsenceMap absent = absences.between(slots.getFirst().day(), slots.getLast().day());
        Long previous = existing.values().stream()
                .filter(t -> t.getAssigneeMemberId() != null)
                .max(Comparator.comparing(ChoreTask::getDueOn)
                        .thenComparing(t -> t.getDueTime() == null ? LocalTime.MIN : t.getDueTime())
                        .thenComparing(ChoreTask::getId))
                .map(ChoreTask::getAssigneeMemberId).orElse(null);
        for (Slot slot : slots) {
            if (existing.containsKey(slot.key())) {
                continue;
            }
            Long assignee = choose(chore, slot.day(), previous, absent, today);
            ChoreTask task = tasks.save(new ChoreTask(chore, slot.day(), slot.time(), assignee));
            existing.put(task.getSlotKey(), task);
            if (chore.getDistribution() == Distribution.BALANCED) {
                tasks.flush();
            }
            if (assignee != null) {
                previous = assignee;
            }
        }
    }

    private List<Slot> slots(Chore chore, LocalDate today, Map<String, ChoreTask> existing) {
        LocalDate end = today.plusDays(HORIZON_DAYS);
        List<Slot> result = new ArrayList<>();
        switch (chore.getKind()) {
            case DATES -> dates.findAllByChoreIdOrderByDueOnAscDueTimeAsc(chore.getId()).stream()
                    .filter(d -> !d.getDueOn().isBefore(today) && !d.getDueOn().isAfter(end))
                    .forEach(d -> result.add(new Slot(d.getDueOn(), d.getDueTime())));
            case RECURRING -> {
                LocalDate seed = chore.getStartsOn() != null ? chore.getStartsOn() : today;
                LocalDate from = seed.isBefore(today) ? today : seed;
                if (!from.isAfter(end)) {
                    new Recur<LocalDate>(chore.getRrule()).getDates(seed, from, end).stream()
                            .filter(d -> !d.isBefore(today) && !d.isAfter(end))
                            .distinct().sorted()
                            .forEach(d -> result.add(new Slot(d, chore.getDueTime())));
                }
            }
            case INTERVAL -> {
                boolean hasOpen = existing.values().stream().anyMatch(ChoreTask::isOpen);
                if (!hasOpen) {
                    LocalDate due = nextIntervalDue(chore, today, existing.values());
                    while (existing.containsKey(ChoreTask.slotKey(due, chore.getDueTime()))) {
                        due = due.plusDays(1);
                    }
                    result.add(new Slot(due, chore.getDueTime()));
                }
            }
            case ON_DEMAND -> {
                // wird nicht geplant, nur bei Bedarf gebucht
            }
        }
        result.sort(Comparator.comparing(Slot::day)
                .thenComparing(s -> s.time() == null ? LocalTime.MIN : s.time()));
        return result;
    }

    private LocalDate nextIntervalDue(Chore chore, LocalDate today, java.util.Collection<ChoreTask> existing) {
        ZoneId zone = clock.getZone();
        return existing.stream()
                .filter(t -> !t.isOpen() && t.getCompletedAt() != null)
                .map(t -> t.getCompletedAt().atZone(zone).toLocalDate())
                .max(Comparator.naturalOrder())
                .map(done -> done.plusDays(chore.getIntervalDays()))
                .orElseGet(() -> chore.getStartsOn() != null && chore.getStartsOn().isAfter(today)
                        ? chore.getStartsOn() : today);
    }

    /** Verteilt künftige offene Aufgaben reihum/ausgleichend neu, wenn der Zuständige abwesend ist. */
    @Transactional
    public void reassignAbsent(LocalDate today) {
        Absences.AbsenceMap absent = absences.between(today, today.plusDays(HORIZON_DAYS));
        List<ChoreTask> open = new ArrayList<>(tasks.findAllByStatusAndDueOnGreaterThanEqual(ChoreTaskStatus.OPEN, today));
        open.sort(Comparator.comparing(ChoreTask::getDueOn));
        for (ChoreTask task : open) {
            Distribution distribution = task.getChore().getDistribution();
            if (distribution != Distribution.ROTATION && distribution != Distribution.BALANCED) {
                continue;
            }
            Long current = task.getAssigneeMemberId();
            if (current != null && !absent.isAbsent(current, task.getDueOn())) {
                continue;
            }
            Long replacement = choose(task.getChore(), task.getDueOn(), current, absent, today);
            if (replacement != null || current != null) {
                task.assign(replacement);
                if (distribution == Distribution.BALANCED) {
                    tasks.flush();
                }
            }
        }
    }

    Long choose(Chore chore, LocalDate day, Long previous, Absences.AbsenceMap absent, LocalDate today) {
        List<Long> candidates = chore.getMemberIds();
        return switch (chore.getDistribution()) {
            case OPEN -> null;
            case FIXED -> candidates.isEmpty() ? null : candidates.getFirst();
            case ROTATION -> {
                int start = previous == null ? -1 : candidates.indexOf(previous);
                for (int k = 1; k <= candidates.size(); k++) {
                    Long candidate = candidates.get(Math.floorMod(start + k, candidates.size()));
                    if (!absent.isAbsent(candidate, day)) {
                        yield candidate;
                    }
                }
                yield null;
            }
            case BALANCED -> {
                List<Long> present = candidates.stream().filter(c -> !absent.isAbsent(c, day)).toList();
                if (present.isEmpty()) {
                    yield null;
                }
                Map<Long, Integer> load = load(today);
                yield present.stream().min(Comparator.comparingInt(c -> load.getOrDefault(c, 0))).orElseThrow();
            }
        };
    }

    /** Minuten je Mitglied: Gutschriften der letzten 28 Tage plus geplante offene Aufgaben (mittlerer Aufwand) ab heute. */
    Map<Long, Integer> load(LocalDate today) {
        entityManager.flush();
        Map<Long, Integer> load = new HashMap<>();
        Map<String, Object> params = Map.of(
                "since", java.sql.Timestamp.from(today.minusDays(BALANCE_DAYS).atStartOfDay(clock.getZone()).toInstant()),
                "today", today);
        jdbc.query("""
                SELECT member_id, sum(coalesce(corrected_minutes, minutes)) AS m FROM chore_credits
                WHERE created_at >= :since GROUP BY member_id""", params,
                rs -> { load.merge(rs.getLong("member_id"), rs.getInt("m"), Integer::sum); });
        jdbc.query("""
                SELECT t.assignee_member_id AS member_id, sum(c.minutes_medium) AS m
                FROM chore_tasks t JOIN chores c ON c.id = t.chore_id
                WHERE t.status = 'OPEN' AND t.due_on >= :today AND t.assignee_member_id IS NOT NULL
                GROUP BY t.assignee_member_id""", params,
                rs -> { load.merge(rs.getLong("member_id"), rs.getInt("m"), Integer::sum); });
        return load;
    }
}
