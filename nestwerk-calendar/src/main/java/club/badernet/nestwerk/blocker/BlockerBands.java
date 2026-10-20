package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Blockers as bands for the calendar views. Affected members and admins see the title, others "belegt" or nothing
 * depending on the visibility. Bands of further {@link CalendarBandSource}s are appended.
 */
@Component
public class BlockerBands {

    /** A band on a day; {@code kind} selects colour and icon (e.g. {@code work}). */
    public record Band(String label, String times, String kind, String color) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BlockerRepository blockers;
    private final BlockerSettings settings;
    private final FamilyMemberRepository members;
    private final ZoneId zone;
    private final List<CalendarBandSource> sources;

    public BlockerBands(BlockerRepository blockers, BlockerSettings settings, FamilyMemberRepository members,
                        CalendarSettings calendar, List<CalendarBandSource> sources) {
        this.sources = sources;
        this.blockers = blockers;
        this.settings = settings;
        this.members = members;
        this.zone = calendar.zone();
    }

    /** Bands per day from {@code from} to {@code to} (inclusive), on the day they start. */
    @Transactional(readOnly = true)
    public Map<LocalDate, List<Band>> between(LocalDate from, LocalDate to, User viewer) {
        Map<LocalDate, List<Band>> result = blockerBands(from, to, viewer);
        for (CalendarBandSource source : sources) {
            source.bands(from, to, viewer).forEach((day, bands) ->
                    result.computeIfAbsent(day, d -> new ArrayList<>()).addAll(bands));
        }
        return result;
    }

    private Map<LocalDate, List<Band>> blockerBands(LocalDate from, LocalDate to, User viewer) {
        Map<LocalDate, List<Band>> result = new LinkedHashMap<>();
        List<Blocker> all = blockers.findAll();
        if (all.isEmpty()) {
            return result;
        }
        Map<Long, FamilyMember> people = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        Long own = members.findByUserId(viewer.getId()).map(FamilyMember::getId).orElse(null);
        boolean admin = viewer.isAdministrator();
        for (BlockerSlot slot : BlockerSchedule.slots(all, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), zone, settings.state())) {
            if (slot.day().isBefore(from)) {
                continue;
            }
            Blocker blocker = slot.blocker();
            boolean insider = admin || blocker.getMemberIds().contains(own);
            if (!insider && blocker.getVisibility() == BlockerVisibility.HIDDEN) {
                continue;
            }
            String what = insider || blocker.getVisibility() == BlockerVisibility.TITLE ? blocker.getTitle() : "belegt";
            String times = TIME.format(slot.start().atZone(zone)) + "–" + TIME.format(slot.end().atZone(zone));
            List<FamilyMember> who = blocker.getMemberIds().stream().map(people::get)
                    .filter(java.util.Objects::nonNull).sorted(java.util.Comparator.comparing(FamilyMember::getName))
                    .toList();
            String names = who.stream().map(FamilyMember::getName).collect(Collectors.joining(", "));
            String color = who.size() == 1 ? who.getFirst().getColor() : null;
            result.computeIfAbsent(slot.day(), d -> new ArrayList<>()).add(new Band(names + " · " + what, times,
                    blocker.getKind().name().toLowerCase(Locale.ROOT), color));
        }
        return result;
    }
}
