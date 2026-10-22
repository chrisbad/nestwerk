package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.blocker.BlockerConflicts;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.Conflict;
import club.badernet.nestwerk.blocker.TimeRange;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CalendarEventService {

    private static final Duration MAX_WINDOW = Duration.ofDays(400);
    /** Series are checked against blockers this far ahead. */
    private static final Duration SERIES_CHECK = Duration.ofDays(56);
    private static final Duration MAX_SERIES_DURATION = Duration.ofDays(1);
    public static final int MAX_LOCATION_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 5000;

    private final CalendarEventRepository events;
    private final FamilyMemberRepository members;
    private final EventCategoryRepository categories;
    private final RecurrenceExpander expander;
    private final ZoneId zone;
    private final CalendarAccess access;
    private final ApplicationEventPublisher publisher;
    private final BlockerConflicts blockers;

    public CalendarEventService(CalendarEventRepository events, FamilyMemberRepository members,
                                EventCategoryRepository categories, RecurrenceExpander expander,
                                CalendarSettings settings, CalendarAccess access,
                                ApplicationEventPublisher publisher, BlockerConflicts blockers) {
        this.events = events;
        this.members = members;
        this.categories = categories;
        this.expander = expander;
        this.zone = settings.zone();
        this.access = access;
        this.publisher = publisher;
        this.blockers = blockers;
    }

    @Transactional(readOnly = true)
    public List<Occurrence> findOccurrences(Instant from, Instant to, User viewer) {
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
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
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
        CalendarEvent event = events.findById(id).orElseThrow(EventNotFoundException::new);
        if (!isVisible(event, viewer)) {
            throw new EventNotFoundException();
        }
        return event;
    }

    @Transactional
    public CalendarEvent create(EventInput in, User actor) {
        return create(in, actor, UUID.randomUUID() + "@nestwerk");
    }

    /** Creates an event with a given UID, e.g. when importing an iCal file. */
    @Transactional
    public CalendarEvent create(EventInput in, User actor, String uid) {
        access.require(actor, CalendarPermission.CALENDAR_EVENT_CREATE);
        if (in.privateEvent()) {
            access.require(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
        }
        TimeRange times = in.timeChange().in(zone);
        CalendarEvent event = new CalendarEvent(uid, requireTitle(in.title()),
                times.start(), times.end(), in.allDay(), zone.getId(), actor.getId());
        applyDetails(event, in);
        refuseBlocked(conflicts(event, null, actor));
        CalendarEvent saved = events.save(event);
        if (saved.isAbsence()) {
            publisher.publishEvent(new AbsenceChangedEvent(saved.getId()));
        }
        return saved;
    }

    @Transactional
    public void update(Long id, EventInput in, User actor) {
        CalendarEvent event = editable(id, actor);
        if (in.privateEvent() && !event.isPrivateEvent()) {
            access.require(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
        }
        boolean wasAbsence = event.isAbsence();
        TimeRange times = in.timeChange().in(zone);
        boolean seriesChanged = !times.start().equals(event.getStartsAt())
                || !Objects.equals(normalizeRule(in.rrule()), event.getRrule());
        refuseBlocked(conflicts(in, id, actor));
        event.setTitle(requireTitle(in.title()));
        event.setTimes(times.start(), times.end(), in.allDay());
        applyDetails(event, in);
        if (seriesChanged) {
            event.clearExceptions();
        }
        event.markChanged();
        absenceChanged(event, wasAbsence);
    }

    /**
     * Moves a single event to another day; times of day and duration stay the same in local time.
     * Returns warnings of blockers (blocks prevent the move).
     */
    @Transactional
    public List<Conflict> moveToDate(Long id, LocalDate target, User actor) {
        CalendarEvent event = editable(id, actor);
        ZonedDateTime start = event.getStartsAt().atZone(zone);
        long days = ChronoUnit.DAYS.between(start.toLocalDate(), target);
        if (days == 0) {
            return List.of();
        }
        ZonedDateTime end = event.getEndsAt().atZone(zone);
        TimeChange change = event.isAllDay()
                ? new TimeChange.AllDay(start.toLocalDate().plusDays(days), end.toLocalDate().minusDays(1).plusDays(days))
                : new TimeChange.Timed(start.toLocalDateTime().plusDays(days), end.toLocalDateTime().plusDays(days));
        return reschedule(id, change, actor);
    }

    /**
     * Gives a single (non-recurring) event new times, also switching between all-day and timed. Returns warnings of
     * blockers; blocks prevent the change.
     */
    @Transactional
    public List<Conflict> reschedule(Long id, TimeChange change, User actor) {
        CalendarEvent event = editable(id, actor);
        if (event.getRrule() != null) {
            throw new IllegalArgumentException(
                    "Bei Terminserien bitte ein einzelnes Vorkommen oder die ganze Serie verschieben.");
        }
        TimeRange times = change.in(zone);
        List<Conflict> found = blockers.check(event.getMemberIds(), List.of(times), actor, event.getId());
        refuseBlocked(found);
        boolean wasAbsence = event.isAbsence();
        event.setTimes(times.start(), times.end(), change.allDay());
        event.markChanged();
        absenceChanged(event, wasAbsence);
        return found;
    }

    /**
     * Gives one occurrence of a series new times; title, notes and place of the occurrence stay. An occurrence
     * cannot switch between all-day and timed because that is a property of the whole series.
     */
    @Transactional
    public List<Conflict> rescheduleOccurrence(Long id, Instant recurrenceId, TimeChange change, User actor) {
        return changeOccurrence(id, recurrenceId, null, change, actor);
    }

    /**
     * Gives one occurrence of a series a new title ({@code null} keeps it) and new times; notes and place stay.
     * Returns warnings of blockers; blocks prevent the change.
     */
    @Transactional
    public List<Conflict> changeOccurrence(Long id, Instant recurrenceId, String title, TimeChange change,
                                           User actor) {
        CalendarEvent event = editableSeries(id, recurrenceId, actor);
        if (change.allDay() != event.isAllDay()) {
            throw new IllegalArgumentException("Ein einzelnes Vorkommen kann nicht zwischen ganztägig und mit "
                    + "Uhrzeit wechseln. Das geht nur für die ganze Serie im Formular.");
        }
        TimeRange times = change.in(zone);
        List<Conflict> found = blockers.check(event.getMemberIds(), List.of(times), actor, event.getId());
        refuseBlocked(found);
        Optional<CalendarEventException> current = event.exceptionFor(recurrenceId);
        // the series title is not copied, so that renaming the series still reaches this occurrence
        String newTitle = title == null ? current.map(CalendarEventException::getTitle).orElse(null)
                : requireTitle(title).equals(event.getTitle()) ? null : requireTitle(title);
        event.overrideOccurrence(recurrenceId, newTitle,
                current.map(CalendarEventException::getDescription).orElse(null),
                current.map(CalendarEventException::getLocation).orElse(null), times.start(), times.end());
        event.markChanged();
        absenceChanged(event, false);
        return found;
    }

    /**
     * Moves a timed series to another time of day and/or gives it a new duration; the days stay. Cancelled and
     * changed occurrences stay attached to their day.
     */
    @Transactional
    public List<Conflict> rescheduleSeries(Long id, LocalTime start, Duration duration, User actor) {
        CalendarEvent event = editable(id, actor);
        if (event.getRrule() == null) {
            throw new IllegalArgumentException("Dieser Termin ist keine Serie.");
        }
        if (event.isAllDay()) {
            throw new IllegalArgumentException(
                    "Bei ganztägigen Serien lässt sich nur ein einzelnes Vorkommen verschieben.");
        }
        if (start == null || duration == null || duration.isNegative() || duration.isZero()
                || duration.compareTo(MAX_SERIES_DURATION) > 0) {
            throw new IllegalArgumentException("Eine Serie braucht einen Beginn und eine Dauer von höchstens 24 Stunden.");
        }
        ZonedDateTime newStart = event.getStartsAt().atZone(zone).with(start);
        CalendarEvent draft = new CalendarEvent("check", "Prüfung", newStart.toInstant(),
                newStart.plus(duration).toInstant(), false, zone.getId(), actor.getId());
        draft.setRrule(event.getRrule());
        draft.setMemberIds(event.getMemberIds());
        List<Conflict> found = conflicts(draft, event.getId(), actor);
        refuseBlocked(found);
        boolean wasAbsence = event.isAbsence();
        event.setTimes(draft.getStartsAt(), draft.getEndsAt(), false);
        event.moveExceptionsTo(start, zone);
        event.markChanged();
        absenceChanged(event, wasAbsence);
        return found;
    }

    /**
     * Dragging into another person's column: {@code to} takes the place of {@code from} (for a series in the whole
     * series), other members stay; {@code null} as {@code from} only adds, {@code null} as {@code to} only removes.
     * Then {@code timeChange} (e.g. {@code () -> reschedule(...)}, or {@code List::of} for none) gives new times in the
     * same transaction. Blocks are checked for the new members at the new times; they prevent both changes.
     */
    @Transactional
    public List<Conflict> moveToMember(Long id, Long from, Long to, Supplier<List<Conflict>> timeChange, User actor) {
        CalendarEvent event = editable(id, actor);
        if (to != null && !members.existsById(to)) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        Set<Long> ids = new LinkedHashSet<>(event.getMemberIds());
        if (from != null) {
            ids.remove(from);
        }
        if (to != null) {
            ids.add(to);
        }
        boolean wasAbsence = event.isAbsence();
        event.setMemberIds(ids);
        List<Conflict> found = new ArrayList<>(timeChange.get());
        CalendarEvent draft = new CalendarEvent("check", "Prüfung", event.getStartsAt(), event.getEndsAt(),
                event.isAllDay(), zone.getId(), actor.getId());
        draft.setRrule(event.getRrule());
        draft.setMemberIds(ids);
        List<Conflict> forMembers = conflicts(draft, event.getId(), actor);
        refuseBlocked(forMembers);
        forMembers.stream().filter(c -> !found.contains(c)).forEach(found::add);
        event.markChanged();
        absenceChanged(event, wasAbsence);
        return found;
    }

    @Transactional
    public void delete(Long id, User actor) {
        CalendarEvent event = get(id, actor);
        if (!canDelete(event, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Löschen dieses Termins");
        }
        events.delete(event);
        absenceChanged(event, false);
    }

    @Transactional
    public void cancelOccurrence(Long id, Instant recurrenceId, User actor) {
        CalendarEvent event = editableSeries(id, recurrenceId, actor);
        event.cancelOccurrence(recurrenceId);
        event.markChanged();
        absenceChanged(event, false);
    }

    @Transactional
    public void overrideOccurrence(Long id, Instant recurrenceId, OccurrenceInput in, User actor) {
        CalendarEvent event = editableSeries(id, recurrenceId, actor);
        TimeRange times = new TimeChange.Timed(in.start(), in.end()).in(zone);
        refuseBlocked(blockers.check(event.getMemberIds(), List.of(times), actor, event.getId()));
        event.overrideOccurrence(recurrenceId, requireTitle(in.title()), description(in.description()),
                location(in.location()), times.start(), times.end());
        event.markChanged();
        absenceChanged(event, false);
    }

    private void absenceChanged(CalendarEvent event, boolean wasAbsence) {
        if (wasAbsence || event.isAbsence()) {
            events.flush();
            publisher.publishEvent(new AbsenceChangedEvent(event.getId()));
        }
    }

    /** All events the viewer may see, with loaded exceptions (for the export). */
    @Transactional(readOnly = true)
    public List<CalendarEvent> visibleEvents(User viewer) {
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
        List<CalendarEvent> list = events.findCandidates(Instant.parse("1900-01-01T00:00:00Z"),
                Instant.parse("2300-01-01T00:00:00Z"), viewer.getId(), viewer.isAdministrator());
        list.forEach(e -> e.getExceptions().size());
        return list.stream().sorted(Comparator.comparing(CalendarEvent::getStartsAt)).toList();
    }

    /** The visible event with this UID, if any. */
    @Transactional(readOnly = true)
    public Optional<CalendarEvent> findVisibleByUid(String uid, User viewer) {
        access.require(viewer, CalendarPermission.CALENDAR_VIEW);
        return events.findByUid(uid).filter(e -> isVisible(e, viewer));
    }

    public boolean canCreate(User actor) {
        return access.has(actor, CalendarPermission.CALENDAR_EVENT_CREATE);
    }

    public boolean canCreatePrivate(User actor) {
        return access.has(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
    }

    public boolean canEdit(CalendarEvent event, User actor) {
        return canEdit(event.getCreatedBy(), actor);
    }

    /** May the actor edit an event created by {@code createdBy}? */
    public boolean canEdit(Long createdBy, User actor) {
        return access.has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_ALL)
                || (access.has(actor, CalendarPermission.CALENDAR_EVENT_EDIT_OWN) && createdBy.equals(actor.getId()));
    }

    public boolean canDelete(CalendarEvent event, User actor) {
        return canDelete(event.getCreatedBy(), actor);
    }

    /** May the actor delete an event created by {@code createdBy}? */
    public boolean canDelete(Long createdBy, User actor) {
        return access.has(actor, CalendarPermission.CALENDAR_EVENT_DELETE_ALL)
                || (access.has(actor, CalendarPermission.CALENDAR_EVENT_DELETE_OWN) && createdBy.equals(actor.getId()));
    }

    private CalendarEvent editable(Long id, User actor) {
        CalendarEvent event = get(id, actor);
        if (!canEdit(event, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Termins");
        }
        return event;
    }

    private CalendarEvent editableSeries(Long id, Instant recurrenceId, User actor) {
        CalendarEvent event = editable(id, actor);
        if (event.getRrule() == null || !expander.isBaseOccurrence(event, recurrenceId)) {
            throw new IllegalArgumentException("Dieses Vorkommen gehört nicht zur Terminserie.");
        }
        return event;
    }

    private void applyDetails(CalendarEvent event, EventInput in) {
        event.setDescription(description(in.description()));
        event.setLocation(location(in.location()));
        event.setPrivateEvent(in.privateEvent());
        event.setAbsence(in.absence());
        event.setNoOverlap(in.noOverlap());
        if (in.categoryId() != null && !categories.existsById(in.categoryId())) {
            throw new IllegalArgumentException("Unbekannte Terminkategorie ausgewählt.");
        }
        event.setCategoryId(in.categoryId());
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

    /**
     * Blocker conflicts of a planned or changed event; for series the occurrences of the next eight weeks.
     * {@code eventId} is the event itself (does not count as busy).
     */
    @Transactional(readOnly = true)
    public List<Conflict> conflicts(EventInput in, Long eventId, User actor) {
        TimeRange times = in.timeChange().in(zone);
        CalendarEvent draft = new CalendarEvent("check", "Prüfung", times.start(), times.end(), in.allDay(),
                zone.getId(), actor.getId());
        draft.setRrule(normalizeRule(in.rrule()));
        draft.setMemberIds(in.memberIds() == null ? Set.of() : new HashSet<>(in.memberIds()));
        return conflicts(draft, eventId, actor);
    }

    private List<Conflict> conflicts(CalendarEvent draft, Long eventId, User actor) {
        if (draft.getMemberIds().isEmpty()) {
            return List.of();
        }
        List<TimeRange> ranges = new ArrayList<>();
        if (draft.getRrule() == null) {
            ranges.add(new TimeRange(draft.getStartsAt(), draft.getEndsAt()));
        } else {
            for (Occurrence o : expander.expand(draft, draft.getStartsAt(), draft.getStartsAt().plus(SERIES_CHECK))) {
                ranges.add(new TimeRange(o.start().toInstant(), o.end().toInstant()));
            }
        }
        return blockers.check(draft.getMemberIds(), ranges, actor, eventId);
    }

    private static void refuseBlocked(List<Conflict> found) {
        List<String> reasons = found.stream().filter(c -> c.strength() == BlockerStrength.BLOCK)
                .map(Conflict::message).distinct().toList();
        if (!reasons.isEmpty()) {
            throw new BlockedException(String.join(" ", reasons) + " Dort kann gerade niemand eingeplant werden.");
        }
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
}
