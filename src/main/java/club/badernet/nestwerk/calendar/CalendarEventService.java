package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CalendarEventService {

    private static final Duration MAX_WINDOW = Duration.ofDays(400);
    public static final int MAX_LOCATION_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 5000;

    private final CalendarEventRepository events;
    private final FamilyMemberRepository members;
    private final RecurrenceExpander expander;
    private final ZoneId zone;
    private final PermissionCatalog permissions;

    public CalendarEventService(CalendarEventRepository events, FamilyMemberRepository members,
                                RecurrenceExpander expander, CalendarSettings settings,
                                PermissionCatalog permissions) {
        this.events = events;
        this.members = members;
        this.expander = expander;
        this.zone = settings.zone();
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<Occurrence> findOccurrences(Instant from, Instant to, User viewer) {
        require(viewer, CalendarPermission.CALENDAR_VIEW);
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Ungültiger Zeitraum.");
        }
        return events.findCandidates(from, to, viewer.getId(), viewer.isAdministrator()).stream()
                .flatMap(e -> expander.expand(e, from, to).stream())
                .sorted(Comparator.comparing(Occurrence::start).thenComparing(Occurrence::title))
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarEvent get(Long id, User viewer) {
        require(viewer, CalendarPermission.CALENDAR_VIEW);
        CalendarEvent event = events.findById(id).orElseThrow(EventNotFoundException::new);
        if (!isVisible(event, viewer)) {
            throw new EventNotFoundException();
        }
        return event;
    }

    @Transactional
    public CalendarEvent create(EventInput in, User actor) {
        require(actor, CalendarPermission.CALENDAR_EVENT_CREATE);
        if (in.privateEvent()) {
            require(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
        }
        Instant[] times = times(in);
        CalendarEvent event = new CalendarEvent(UUID.randomUUID() + "@nestwerk", requireTitle(in.title()),
                times[0], times[1], in.allDay(), zone.getId(), actor.getId());
        applyDetails(event, in);
        return events.save(event);
    }

    @Transactional
    public void update(Long id, EventInput in, User actor) {
        CalendarEvent event = get(id, actor);
        if (!canEdit(event, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Termins");
        }
        if (in.privateEvent() && !event.isPrivateEvent()) {
            require(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
        }
        Instant[] times = times(in);
        boolean seriesChanged = !times[0].equals(event.getStartsAt())
                || !java.util.Objects.equals(normalizeRule(in.rrule()), event.getRrule());
        event.setTitle(requireTitle(in.title()));
        event.setTimes(times[0], times[1], in.allDay());
        applyDetails(event, in);
        if (seriesChanged) {
            event.clearExceptions();
        }
        event.markChanged();
    }

    @Transactional
    public void delete(Long id, User actor) {
        CalendarEvent event = get(id, actor);
        if (!canDelete(event, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Löschen dieses Termins");
        }
        events.delete(event);
    }

    @Transactional
    public void cancelOccurrence(Long id, Instant recurrenceId, User actor) {
        CalendarEvent event = editableSeries(id, recurrenceId, actor);
        event.cancelOccurrence(recurrenceId);
        event.markChanged();
    }

    @Transactional
    public void overrideOccurrence(Long id, Instant recurrenceId, OccurrenceInput in, User actor) {
        CalendarEvent event = editableSeries(id, recurrenceId, actor);
        Instant start = in.start().atZone(zone).toInstant();
        Instant end = in.end().atZone(zone).toInstant();
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Das Ende muss nach dem Beginn liegen.");
        }
        event.overrideOccurrence(recurrenceId, requireTitle(in.title()), description(in.description()),
                location(in.location()), start, end);
        event.markChanged();
    }

    public boolean canEdit(CalendarEvent event, User actor) {
        return has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_ALL)
                || (has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_OWN)
                && event.getCreatedBy().equals(actor.getId()));
    }

    public boolean canDelete(CalendarEvent event, User actor) {
        return has(actor, CalendarPermission.CALENDAR_EVENT_DELETE_ALL)
                || (has(actor, CalendarPermission.CALENDAR_EVENT_DELETE_OWN)
                && event.getCreatedBy().equals(actor.getId()));
    }

    private CalendarEvent editableSeries(Long id, Instant recurrenceId, User actor) {
        CalendarEvent event = get(id, actor);
        if (!canEdit(event, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Termins");
        }
        if (event.getRrule() == null || !expander.isBaseOccurrence(event, recurrenceId)) {
            throw new IllegalArgumentException("Dieses Vorkommen gehört nicht zur Terminserie.");
        }
        return event;
    }

    private void applyDetails(CalendarEvent event, EventInput in) {
        event.setDescription(description(in.description()));
        event.setLocation(location(in.location()));
        event.setPrivateEvent(in.privateEvent());
        String rule = normalizeRule(in.rrule());
        if (rule != null) {
            expander.validate(rule, event.getStartsAt().atZone(zone));
        }
        event.setRrule(rule);
        Set<Long> ids = in.memberIds() == null ? Set.of() : new HashSet<>(in.memberIds());
        if (!ids.isEmpty() && members.findAllById(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        event.setMemberIds(ids);
    }

    private Instant[] times(EventInput in) {
        if (in.startDate() == null || in.endDate() == null) {
            throw new IllegalArgumentException("Start- und Enddatum sind erforderlich.");
        }
        if (in.allDay()) {
            if (in.endDate().isBefore(in.startDate())) {
                throw new IllegalArgumentException("Das Enddatum darf nicht vor dem Startdatum liegen.");
            }
            return new Instant[]{
                    in.startDate().atStartOfDay(zone).toInstant(),
                    in.endDate().plusDays(1).atStartOfDay(zone).toInstant()};
        }
        if (in.startTime() == null || in.endTime() == null) {
            throw new IllegalArgumentException("Start- und Endzeit sind erforderlich.");
        }
        Instant start = ZonedDateTime.of(in.startDate(), in.startTime(), zone).toInstant();
        Instant end = ZonedDateTime.of(in.endDate(), in.endTime(), zone).toInstant();
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Das Ende muss nach dem Beginn liegen.");
        }
        return new Instant[]{start, end};
    }

    private String normalizeRule(String rrule) {
        if (rrule == null || rrule.isBlank()) {
            return null;
        }
        String rule = rrule.trim().replaceFirst("(?i)^RRULE:", "");
        if (rule.length() > 500) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist zu lang.");
        }
        return rule;
    }

    private String requireTitle(String title) {
        String cleaned = title == null ? "" : title.trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) {
            throw new IllegalArgumentException("Der Titel muss 1–200 Zeichen lang sein.");
        }
        return cleaned;
    }

    private String location(String location) {
        return limited(location, MAX_LOCATION_LENGTH, "Der Ort darf höchstens " + MAX_LOCATION_LENGTH + " Zeichen lang sein.");
    }

    private String description(String description) {
        return limited(description, MAX_DESCRIPTION_LENGTH,
                "Die Notizen dürfen höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen lang sein.");
    }

    private String limited(String text, int maxLength, String message) {
        String cleaned = clean(text);
        if (cleaned != null && cleaned.length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
        return cleaned;
    }

    private String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private boolean isVisible(CalendarEvent event, User viewer) {
        return !event.isPrivateEvent()
                || event.getCreatedBy().equals(viewer.getId())
                || viewer.isAdministrator();
    }

    private boolean has(User user, CalendarPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    private void require(User user, CalendarPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung: " + permission.name());
        }
    }
}
