package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Ob ein Nutzer gerade in einem Blocker mit Ruhe-Modus ist – und bis wann. */
@Component
public class QuietHours {

    private final BlockerRepository blockers;
    private final FamilyMemberRepository members;
    private final BlockerSettings settings;
    private final ZoneId zone;

    public QuietHours(BlockerRepository blockers, FamilyMemberRepository members, BlockerSettings settings,
                      CalendarSettings calendar) {
        this.blockers = blockers;
        this.members = members;
        this.settings = settings;
        this.zone = calendar.zone();
    }

    /** Ende der laufenden Ruhezeit des Nutzers (bei aneinandergrenzenden Blockern das späteste Ende). */
    @Transactional(readOnly = true)
    public Optional<Instant> quietUntil(Long userId, Instant now) {
        Optional<Long> member = members.findByUserId(userId).map(FamilyMember::getId);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        List<Blocker> quiet = blockers.findAllByMemberId(member.get()).stream().filter(Blocker::isQuiet).toList();
        if (quiet.isEmpty()) {
            return Optional.empty();
        }
        Instant until = null;
        Instant probe = now;
        // aneinandergrenzende Ruhezeiten (z. B. Schlaf und Morgenruhe) zusammenfassen, höchstens zwei Tage voraus
        for (int i = 0; i < 8; i++) {
            Instant at = probe;
            Optional<Instant> end = BlockerSchedule.slots(quiet, at, at.plusSeconds(1), zone, settings.state()).stream()
                    .map(BlockerSlot::end).max(Comparator.naturalOrder());
            if (end.isEmpty() || end.get().isAfter(now.plusSeconds(2 * 86_400L))) {
                break;
            }
            until = end.get();
            probe = until;
        }
        return Optional.ofNullable(until);
    }
}
