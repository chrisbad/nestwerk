package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Times in which the actor may not (block) or should not (warn) plan a member, with the same strength rules as
 * {@link BlockerConflicts}. The calendar shows them as live feedback while dragging; saving checks again.
 */
@Component
public class BlockerZones {

    /** A blocked or warned time of one member. */
    public record Zone(Long memberId, Instant start, Instant end, BlockerStrength strength, String label) {
    }

    private final BusyTimes busy;
    private final FamilyMemberRepository members;

    public BlockerZones(BusyTimes busy, FamilyMemberRepository members) {
        this.busy = busy;
        this.members = members;
    }

    /** Zones touching the days {@code from} to {@code to} (inclusive). */
    @Transactional(readOnly = true)
    public List<Zone> between(LocalDate from, LocalDate to, User actor) {
        Instant start = from.atStartOfDay(busy.zone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(busy.zone()).toInstant();
        Long actorMember = members.findByUserId(actor.getId()).map(FamilyMember::getId).orElse(null);
        List<Zone> result = new ArrayList<>();
        for (FamilyMember person : members.findAll()) {
            for (BlockerSlot slot : busy.blockerSlots(person.getId(), start, end)) {
                Blocker blocker = slot.blocker();
                boolean insider = actor.isAdministrator() || person.getId().equals(actorMember)
                        || blocker.getMemberIds().contains(actorMember);
                BlockerConflicts.strength(blocker, person.getId(), actorMember).ifPresent(strength ->
                        result.add(new Zone(person.getId(), slot.start(), slot.end(), strength,
                                person.getName() + " · " + label(blocker, insider))));
            }
        }
        return result;
    }

    private static String label(Blocker blocker, boolean insider) {
        if (insider || blocker.getVisibility() == BlockerVisibility.TITLE) {
            return blocker.getTitle();
        }
        return blocker.getVisibility() == BlockerVisibility.BUSY ? "belegt" : "nicht verfügbar";
    }
}
