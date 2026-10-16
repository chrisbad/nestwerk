package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.RecurrenceExpander;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Belegte Zeiten der Personen durch Termine und die Zeitfenster ihrer Blocker. */
@Component
public class BusyTimes {

    private final CalendarEventRepository events;
    private final RecurrenceExpander expander;
    private final BlockerRepository blockers;
    private final BlockerSettings settings;
    private final ZoneId zone;

    public BusyTimes(CalendarEventRepository events, RecurrenceExpander expander, BlockerRepository blockers,
                     BlockerSettings settings, CalendarSettings calendar) {
        this.events = events;
        this.expander = expander;
        this.blockers = blockers;
        this.settings = settings;
        this.zone = calendar.zone();
    }

    public ZoneId zone() {
        return zone;
    }

    /** Termine je Person im Zeitraum (alle, auch private); {@code ignoreEventId} wird übersprungen. */
    @Transactional(readOnly = true)
    public Map<Long, List<TimeRange>> events(Collection<Long> memberIds, Instant from, Instant to,
                                             Long ignoreEventId) {
        Map<Long, List<TimeRange>> result = new HashMap<>();
        if (memberIds.isEmpty()) {
            return result;
        }
        for (CalendarEvent event : events.findCandidates(from, to, 0L, true)) {
            if (event.getId().equals(ignoreEventId)) {
                continue;
            }
            Set<Long> involved = event.getMemberIds();
            if (involved.stream().noneMatch(memberIds::contains)) {
                continue;
            }
            for (Occurrence o : expander.expand(event, from, to)) {
                TimeRange range = new TimeRange(o.start().toInstant(), o.end().toInstant());
                for (Long member : involved) {
                    if (memberIds.contains(member)) {
                        result.computeIfAbsent(member, k -> new ArrayList<>()).add(range);
                    }
                }
            }
        }
        return result;
    }

    /** Zeitfenster der Blocker der Person im Zeitraum. */
    @Transactional(readOnly = true)
    public List<BlockerSlot> blockerSlots(Long memberId, Instant from, Instant to) {
        return BlockerSchedule.slots(blockers.findAllByMemberId(memberId), from, to, zone, settings.state());
    }
}
