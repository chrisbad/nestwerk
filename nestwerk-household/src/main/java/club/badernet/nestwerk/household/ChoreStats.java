package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Auswertung: wer hat im Zeitraum wie viele Minuten eingebracht, getrennt nach Aufwandsgrad gezählt, und was ist
 * überfällig liegen geblieben. Ohne Recht {@code STATS} sieht man nur sich selbst.
 */
@Service
public class ChoreStats {

    /** Werte eines Mitglieds im Zeitraum. */
    public record MemberStats(FamilyMember member, int minutes, int easy, int medium, int hard, int overdue) {

        public int count() {
            return easy + medium + hard;
        }
    }

    private final ChoreCreditRepository credits;
    private final ChoreTaskRepository tasks;
    private final FamilyMemberRepository members;
    private final HouseholdAccess access;
    private final Clock clock;

    public ChoreStats(ChoreCreditRepository credits, ChoreTaskRepository tasks, FamilyMemberRepository members,
                      HouseholdAccess access, Clock clock) {
        this.credits = credits;
        this.tasks = tasks;
        this.members = members;
        this.access = access;
        this.clock = clock;
    }

    public boolean seesAll(User viewer) {
        return access.canAny(viewer, "STATS");
    }

    @Transactional(readOnly = true)
    public List<MemberStats> forPeriod(User viewer, LocalDate from, LocalDate to) {
        List<ChoreCredit> inPeriod = credits(viewer, from, to);
        LocalDate today = LocalDate.now(clock);
        LocalDate overdueUntil = to.isBefore(today) ? to : today.minusDays(1);
        List<ChoreTask> overdue = overdueUntil.isBefore(from) ? List.of()
                : tasks.findAllByStatusAndDueOnBefore(ChoreTaskStatus.OPEN, overdueUntil.plusDays(1)).stream()
                .filter(t -> !t.getDueOn().isBefore(from)).toList();
        List<MemberStats> result = new ArrayList<>();
        for (FamilyMember m : visibleMembers(viewer)) {
            int minutes = 0;
            int easy = 0;
            int medium = 0;
            int hard = 0;
            for (ChoreCredit c : inPeriod) {
                if (!c.getMemberId().equals(m.getId())) {
                    continue;
                }
                minutes += c.effectiveMinutes();
                switch (c.getEffort()) {
                    case EASY -> easy++;
                    case MEDIUM -> medium++;
                    case HARD -> hard++;
                }
            }
            int late = (int) overdue.stream().filter(t -> m.getId().equals(t.getAssigneeMemberId())).count();
            result.add(new MemberStats(m, minutes, easy, medium, hard, late));
        }
        result.sort(Comparator.comparingInt(MemberStats::minutes).reversed()
                .thenComparing(s -> s.member().getName(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Gutschriften im Zeitraum (nach Zeitpunkt der Erledigung), neueste zuerst. */
    @Transactional(readOnly = true)
    public List<ChoreCredit> credits(User viewer, LocalDate from, LocalDate to) {
        return credits(viewer, from, to, null);
    }

    /** Wie {@link #credits(User, LocalDate, LocalDate)}, aber nur die Gutschriften eines Mitglieds; {@code null} heißt alle. */
    @Transactional(readOnly = true)
    public List<ChoreCredit> credits(User viewer, LocalDate from, LocalDate to, Long memberId) {
        Instant start = from.atStartOfDay(clock.getZone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        boolean all = seesAll(viewer);
        Optional<Long> own = access.memberId(viewer);
        return credits.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end).stream()
                .filter(c -> all || own.map(c.getMemberId()::equals).orElse(false))
                .filter(c -> memberId == null || memberId.equals(c.getMemberId()))
                .sorted(Comparator.comparing(ChoreCredit::getCreatedAt).reversed().thenComparing(ChoreCredit::getId))
                .toList();
    }

    @Transactional
    public void correct(Long creditId, Integer minutes, String reason, User actor) {
        if (!access.canAny(actor, "CORRECT")) {
            throw new AccessDeniedException("Keine Berechtigung, Gutschriften zu korrigieren");
        }
        ChoreCredit credit = credits.findById(creditId).orElseThrow(ChoreNotFoundException::new);
        if (minutes == null || minutes < 0 || minutes > ChoreService.MAX_MINUTES) {
            throw new IllegalArgumentException("Die Minuten müssen zwischen 0 und " + ChoreService.MAX_MINUTES + " liegen.");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 200) {
            throw new IllegalArgumentException("Bitte einen Grund angeben (höchstens 200 Zeichen).");
        }
        credit.correct(minutes, why, actor.getId(), clock.instant());
    }

    private List<FamilyMember> visibleMembers(User viewer) {
        if (seesAll(viewer)) {
            return members.findAllByOrderByNameAsc();
        }
        return access.memberId(viewer).flatMap(members::findById).map(List::of).orElse(List.of());
    }
}
