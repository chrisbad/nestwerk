package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.item.CalendarItem;
import club.badernet.nestwerk.item.CalendarItemSource;
import club.badernet.nestwerk.item.ItemAction;
import club.badernet.nestwerk.item.ItemType;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Event occurrences as calendar items. Actions follow the edit and delete rights of the event service. */
@Component
public class EventItems implements CalendarItemSource {

    static final String SCHEDULED = "SCHEDULED";

    private final CalendarEventService events;
    private final EventCategoryService categories;
    private final FamilyMemberRepository members;
    private final ZoneId zone;

    public EventItems(CalendarEventService events, EventCategoryService categories, FamilyMemberRepository members,
                      CalendarSettings settings) {
        this.events = events;
        this.categories = categories;
        this.members = members;
        this.zone = settings.zone();
    }

    @Override
    public ItemType type() {
        return ItemType.EVENT;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CalendarItem> items(LocalDate from, LocalDate to, User viewer) {
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        return items(events.findOccurrences(start, end, viewer), viewer);
    }

    /** The first occurrence of the event; for a series that is the one the series starts with. */
    @Override
    @Transactional(readOnly = true)
    public Optional<CalendarItem> find(Long id, User viewer) {
        CalendarEvent event;
        try {
            event = events.get(id, viewer);
        } catch (EventNotFoundException | AccessDeniedException e) {
            return Optional.empty();
        }
        Instant end = event.getEndsAt().isAfter(event.getStartsAt()) ? event.getEndsAt()
                : event.getStartsAt().plusSeconds(60);
        return items(events.findOccurrences(event.getStartsAt(), end, viewer), viewer).stream()
                .filter(item -> item.id().equals(id)).findFirst();
    }

    private List<CalendarItem> items(List<Occurrence> occurrences, User viewer) {
        Map<Long, EventCategory> byCategory = categories.list(viewer).stream()
                .collect(Collectors.toMap(EventCategory::getId, Function.identity()));
        Map<Long, FamilyMember> people = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        return occurrences.stream().map(o -> item(o, byCategory, people, viewer)).toList();
    }

    private CalendarItem item(Occurrence o, Map<Long, EventCategory> byCategory, Map<Long, FamilyMember> people,
                              User viewer) {
        EventCategory category = o.categoryId() == null ? null : byCategory.get(o.categoryId());
        String color = category != null ? category.getColor().hex()
                : o.memberIds().stream().map(people::get).filter(Objects::nonNull).findFirst()
                .map(FamilyMember::getColor).orElse(null);
        Set<ItemAction> actions = EnumSet.noneOf(ItemAction.class);
        if (events.canEdit(o.createdBy(), viewer)) {
            actions.add(ItemAction.EDIT);
            actions.add(ItemAction.MOVE);
        }
        if (events.canDelete(o.createdBy(), viewer)) {
            actions.add(ItemAction.DELETE);
        }
        return new CalendarItem.Event(o.eventId(), o.title(), o.start().withZoneSameInstant(zone).toLocalDateTime(),
                o.end().withZoneSameInstant(zone).toLocalDateTime(), o.allDay(), o.memberIds(), color, SCHEDULED,
                o.recurring() ? o.eventId() : null, Set.copyOf(actions), o.location(), o.privateEvent(),
                o.categoryId(), o.recurring() ? o.recurrenceId() : null);
    }
}
