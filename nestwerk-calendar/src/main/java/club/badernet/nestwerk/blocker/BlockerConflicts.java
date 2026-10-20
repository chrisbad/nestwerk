package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Prüft, ob Personen zu bestimmten Zeiten eingeplant werden dürfen. Sperren gilt nur für andere: Für die eigene
 * Person höchstens eine Warnung, für erlaubte Planer gar kein Konflikt; „nur anzeigen“ erzeugt nie einen.
 */
@Component
public class BlockerConflicts {

    static final LocalTime DAY_START = LocalTime.of(7, 0);
    static final LocalTime DAY_END = LocalTime.of(21, 0);
    static final int ALTERNATIVE_DAYS = 7;
    static final int MAX_ALTERNATIVES = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BusyTimes busy;
    private final RestGoals goals;
    private final FamilyMemberRepository members;
    private final Clock clock;

    public BlockerConflicts(BusyTimes busy, RestGoals goals, FamilyMemberRepository members, Clock clock) {
        this.busy = busy;
        this.goals = goals;
        this.members = members;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Conflict> check(Set<Long> memberIds, List<TimeRange> times, User actor, Long ignoreEventId) {
        List<Conflict> result = new ArrayList<>();
        if (memberIds == null || memberIds.isEmpty() || times.isEmpty()) {
            return result;
        }
        Instant from = times.stream().map(TimeRange::start).min(Comparator.naturalOrder()).orElseThrow();
        Instant to = times.stream().map(TimeRange::end).max(Comparator.naturalOrder()).orElseThrow();
        Long actorMember = actor == null ? null
                : members.findByUserId(actor.getId()).map(FamilyMember::getId).orElse(null);
        boolean admin = actor != null && actor.isAdministrator();
        Map<Long, FamilyMember> people = members.findAllById(memberIds).stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        for (Long memberId : memberIds) {
            FamilyMember person = people.get(memberId);
            if (person == null) {
                continue;
            }
            Map<Long, Conflict> perBlocker = new LinkedHashMap<>();
            for (BlockerSlot slot : busy.blockerSlots(memberId, from, to)) {
                if (times.stream().noneMatch(t -> t.overlaps(slot.start(), slot.end()))) {
                    continue;
                }
                Blocker blocker = slot.blocker();
                strength(blocker, memberId, actorMember).ifPresent(strength -> perBlocker.merge(blocker.getId(),
                        new Conflict(memberId, person.getName(), blocker.getId(), strength,
                                message(person, slot, memberId.equals(actorMember) || admin
                                        || blocker.getMemberIds().contains(actorMember))),
                        (a, b) -> a.strength().compareTo(b.strength()) >= 0 ? a : b));
            }
            result.addAll(perBlocker.values());
            for (String warning : goals.wouldBreak(memberId, times, ignoreEventId)) {
                result.add(new Conflict(memberId, person.getName(), null, BlockerStrength.WARN,
                        person.getName() + ": " + warning));
            }
        }
        return result;
    }

    public static boolean blocks(List<Conflict> conflicts) {
        return conflicts.stream().anyMatch(c -> c.strength() == BlockerStrength.BLOCK);
    }

    /**
     * Bis zu drei freie Zeiten gleicher Dauer für alle Personen: zuerst am gewünschten Tag (nächstgelegen), dann an
     * den folgenden Tagen, jeweils 7–21 Uhr, außerhalb von Blockern (warnen/sperren) und Terminen.
     */
    @Transactional(readOnly = true)
    public List<TimeRange> alternatives(Set<Long> memberIds, TimeRange wanted, Long ignoreEventId) {
        List<TimeRange> result = new ArrayList<>();
        if (memberIds == null || memberIds.isEmpty()) {
            return result;
        }
        Duration length = wanted.duration();
        ZonedDateTime wish = wanted.start().atZone(busy.zone());
        LocalDate firstDay = wish.toLocalDate();
        Instant from = firstDay.atStartOfDay(busy.zone()).toInstant();
        Instant to = firstDay.plusDays(ALTERNATIVE_DAYS).atStartOfDay(busy.zone()).toInstant();
        List<TimeRange> taken = new ArrayList<>();
        busy.events(memberIds, from, to, ignoreEventId).values().forEach(taken::addAll);
        for (Long memberId : memberIds) {
            for (BlockerSlot slot : busy.blockerSlots(memberId, from, to)) {
                if (slot.blocker().getStrength() != BlockerStrength.SHOW) {
                    taken.add(new TimeRange(slot.start(), slot.end()));
                }
            }
        }
        Instant now = clock.instant();
        for (int d = 0; d < ALTERNATIVE_DAYS && result.size() < MAX_ALTERNATIVES; d++) {
            LocalDate day = firstDay.plusDays(d);
            List<TimeRange> free = new ArrayList<>();
            for (LocalTime t = DAY_START; !t.plus(length).isAfter(DAY_END) && !t.plus(length).isBefore(t);
                 t = t.plusMinutes(15)) {
                Instant start = day.atTime(t).atZone(busy.zone()).toInstant();
                TimeRange candidate = new TimeRange(start, start.plus(length));
                if (start.isAfter(now) && !candidate.overlaps(wanted) && taken.stream().noneMatch(candidate::overlaps)) {
                    free.add(candidate);
                }
            }
            LocalTime wishTime = wish.toLocalTime();
            free.sort(Comparator.comparing(c -> Math.abs(Duration.between(wishTime,
                    c.start().atZone(busy.zone()).toLocalTime()).toMinutes())));
            // höchstens zwei am gewünschten Tag und keine fast gleichen Zeiten
            int perDay = d == 0 ? 2 : 1;
            Set<Integer> hours = new HashSet<>();
            for (TimeRange candidate : free) {
                if (perDay == 0 || result.size() >= MAX_ALTERNATIVES) {
                    break;
                }
                if (hours.add(candidate.start().atZone(busy.zone()).getHour())) {
                    result.add(candidate);
                    perDay--;
                }
            }
        }
        return result;
    }

    /** How strongly the blocker keeps the actor from planning the member; empty = not at all. */
    static Optional<BlockerStrength> strength(Blocker blocker, Long memberId, Long actorMember) {
        if (blocker.getStrength() == BlockerStrength.SHOW) {
            return Optional.empty();
        }
        if (memberId.equals(actorMember)) {
            return Optional.of(BlockerStrength.WARN);
        }
        if (actorMember != null && blocker.getPlannerIds().contains(actorMember)) {
            return Optional.empty();
        }
        return Optional.of(blocker.getStrength());
    }

    private String message(FamilyMember person, BlockerSlot slot, boolean insider) {
        Blocker blocker = slot.blocker();
        String times = TIME.format(slot.start().atZone(busy.zone())) + "–" + TIME.format(slot.end().atZone(busy.zone()));
        if (insider || blocker.getVisibility() == BlockerVisibility.TITLE) {
            String title = blocker.getTitle().equals(blocker.getKind().label()) ? "" : blocker.getTitle() + ", ";
            return person.getName() + " " + blocker.getKind().verb() + " (" + title + times + ").";
        }
        if (blocker.getVisibility() == BlockerVisibility.BUSY) {
            return person.getName() + " ist da nicht verfügbar (" + times + ").";
        }
        return person.getName() + " ist da nicht verfügbar.";
    }
}
