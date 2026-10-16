package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.calendar.CalendarSettings;
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
 * Blocker als Bänder für die Kalenderansichten. Betroffene und Admins sehen den Titel, andere je nach
 * Sichtbarkeit „belegt“ oder gar nichts.
 */
@Component
public class BlockerBands {

    /** Ein Band an einem Tag; {@code kind} für Farbe und Symbol (z. B. {@code work}). */
    public record Band(String label, String times, String kind, String color) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BlockerRepository blockers;
    private final BlockerSettings settings;
    private final FamilyMemberRepository members;
    private final ZoneId zone;

    public BlockerBands(BlockerRepository blockers, BlockerSettings settings, FamilyMemberRepository members,
                        CalendarSettings calendar) {
        this.blockers = blockers;
        this.settings = settings;
        this.members = members;
        this.zone = calendar.zone();
    }

    /** Bänder je Tag von {@code from} bis {@code to} (einschließlich), am Tag ihres Beginns. */
    @Transactional(readOnly = true)
    public Map<LocalDate, List<Band>> between(LocalDate from, LocalDate to, User viewer) {
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
