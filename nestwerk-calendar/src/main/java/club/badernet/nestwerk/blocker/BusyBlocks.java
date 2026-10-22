package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Blockers and further timed ranges of other modules ({@link CalendarBandSource}) as exact time blocks per member
 * and day for the calendar grid. Visibility follows {@link BlockerBands}: affected members and admins see the title,
 * others "belegt" or nothing. All strengths are delivered. A range across midnight is cut at the day boundary.
 */
@Component
public class BusyBlocks {

    static final String BUSY_LABEL = "belegt";

    private final BlockerRepository blockers;
    private final BlockerSettings settings;
    private final FamilyMemberRepository members;
    private final ZoneId zone;
    private final List<CalendarBandSource> sources;

    public BusyBlocks(BlockerRepository blockers, BlockerSettings settings, FamilyMemberRepository members,
                      CalendarSettings calendar, List<CalendarBandSource> sources) {
        this.blockers = blockers;
        this.settings = settings;
        this.members = members;
        this.zone = calendar.zone();
        this.sources = sources;
    }

    /** Blocks touching the days {@code from} to {@code to} (inclusive), sorted by start. */
    @Transactional(readOnly = true)
    public List<BusyBlock> between(LocalDate from, LocalDate to, User viewer) {
        List<BusyBlock> result = new ArrayList<>(blockerBlocks(from, to, viewer));
        sources.forEach(source -> result.addAll(source.blocks(from, to, viewer)));
        return result.stream().flatMap(block -> cutPerDay(block, from, to).stream())
                .sorted(Comparator.comparing(BusyBlock::start)).toList();
    }

    private List<BusyBlock> blockerBlocks(LocalDate from, LocalDate to, User viewer) {
        List<Blocker> all = blockers.findAll();
        List<BusyBlock> result = new ArrayList<>();
        if (all.isEmpty()) {
            return result;
        }
        Map<Long, FamilyMember> people = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        Long own = members.findByUserId(viewer.getId()).map(FamilyMember::getId).orElse(null);
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        for (BlockerSlot slot : BlockerSchedule.slots(all, start, end, zone, settings.state())) {
            Blocker blocker = slot.blocker();
            boolean insider = viewer.isAdministrator() || blocker.getMemberIds().contains(own);
            if (!insider && blocker.getVisibility() == BlockerVisibility.HIDDEN) {
                continue;
            }
            String label = insider || blocker.getVisibility() == BlockerVisibility.TITLE
                    ? blocker.getTitle() : BUSY_LABEL;
            String kind = blocker.getKind().name().toLowerCase(Locale.ROOT);
            blocker.getMemberIds().stream().filter(people::containsKey).forEach(memberId ->
                    result.add(new BusyBlock(memberId, slot.start(), slot.end(), kind, label, blocker.getStrength())));
        }
        return result;
    }

    private List<BusyBlock> cutPerDay(BusyBlock block, LocalDate from, LocalDate to) {
        List<BusyBlock> parts = new ArrayList<>();
        LocalDate day = block.start().atZone(zone).toLocalDate();
        LocalDate lastDay = block.end().atZone(zone).toLocalDate();
        if (block.end().equals(lastDay.atStartOfDay(zone).toInstant())) {
            lastDay = lastDay.minusDays(1);
        }
        for (; !day.isAfter(lastDay); day = day.plusDays(1)) {
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            Instant partStart = block.start().isAfter(dayStart) ? block.start() : dayStart;
            Instant partEnd = block.end().isBefore(dayEnd) ? block.end() : dayEnd;
            parts.add(new BusyBlock(block.memberId(), partStart, partEnd, block.kind(), block.label(),
                    block.strength()));
        }
        return parts;
    }
}
