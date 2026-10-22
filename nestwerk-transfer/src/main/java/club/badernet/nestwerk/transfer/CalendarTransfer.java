package club.badernet.nestwerk.transfer;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarIcs;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exports and imports the calendar as iCal file. The same UID means: the same event is updated. Categories travel
 * by name; unknown names are imported without a category.
 */
@Service
public class CalendarTransfer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CalendarEventService events;
    private final EventCategoryService categories;
    private final CalendarEventRepository repository;
    private final RowRunner runner;
    private final ZoneId zone;

    public CalendarTransfer(CalendarEventService events, EventCategoryService categories,
                            CalendarEventRepository repository, RowRunner runner, CalendarSettings settings) {
        this.events = events;
        this.categories = categories;
        this.repository = repository;
        this.runner = runner;
        this.zone = settings.zone();
    }

    @Transactional(readOnly = true)
    public String exportIcs(User viewer) {
        Map<Long, String> names = categories.list(viewer).stream()
                .collect(Collectors.toMap(EventCategory::getId, EventCategory::getName));
        return CalendarIcs.write(events.visibleEvents(viewer), zone, names);
    }

    /** Preview ({@code commit = false}) or import. */
    public ImportReport importIcs(byte[] data, User actor, boolean commit) {
        if (!events.canCreate(actor)) {
            throw new AccessDeniedException("Keine Berechtigung, Termine anzulegen");
        }
        CalendarIcs.ParseResult parsed = CalendarIcs.parse(data, zone);
        List<ImportReport.Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CalendarIcs.Parsed p : parsed.events()) {
            String detail = p.allDay()
                    ? p.startDate().format(DAY) + (p.endDate().equals(p.startDate()) ? "" : " – " + p.endDate().format(DAY))
                    : p.start().format(WHEN) + (p.rrule() != null ? ", Serie" : "");
            if (!seen.add(p.uid())) {
                rows.add(ImportReport.Row.skip(p.title(), detail, "doppelt in der Datei"));
                continue;
            }
            rows.add(runner.run(p.title(), detail, commit, () -> apply(p, detail, actor)));
        }
        return new ImportReport(rows, parsed.skipped(), commit);
    }

    private ImportReport.Row apply(CalendarIcs.Parsed p, String detail, User actor) {
        if (p.privateEvent() && !events.canCreatePrivate(actor)) {
            return ImportReport.Row.skip(p.title(), detail, "privater Termin, dafür fehlt das Recht");
        }
        Long categoryId = categories.findByName(p.category(), actor).map(EventCategory::getId).orElse(null);
        var existing = events.findVisibleByUid(p.uid(), actor);
        if (existing.isPresent()) {
            CalendarEvent current = existing.get();
            if (!events.canEdit(current, actor)) {
                return ImportReport.Row.skip(p.title(), detail, "vorhanden, Bearbeiten nicht erlaubt");
            }
            Long keptCategory = categoryId != null ? categoryId : current.getCategoryId().orElse(null);
            events.update(current.getId(), input(p, current.getMemberIds(), current.isAbsence(), keptCategory,
                    p.noOverlap() || current.isNoOverlap()), actor);
            return new ImportReport.Row(p.title(), detail, ImportReport.Action.UPDATE, null);
        }
        if (repository.findByUid(p.uid()).isPresent()) {
            return ImportReport.Row.skip(p.title(), detail, "UID gehört zu einem anderen, nicht sichtbaren Termin");
        }
        events.create(input(p, Set.of(), false, categoryId, p.noOverlap()), actor, p.uid());
        return new ImportReport.Row(p.title(), detail, ImportReport.Action.NEW, null);
    }

    private static EventInput input(CalendarIcs.Parsed p, Set<Long> members, boolean absence, Long categoryId,
                                    boolean noOverlap) {
        String title = p.title().length() > 200 ? p.title().substring(0, 200) : p.title();
        if (p.allDay()) {
            return new EventInput(title, p.description(), p.location(), p.startDate(), null, p.endDate(), null, true,
                    p.rrule(), p.privateEvent(), members, absence, categoryId, noOverlap);
        }
        return new EventInput(title, p.description(), p.location(), p.start().toLocalDate(),
                p.start().toLocalTime(), p.end().toLocalDate(), p.end().toLocalTime(), false, p.rrule(),
                p.privateEvent(), members, absence, categoryId, noOverlap);
    }
}
