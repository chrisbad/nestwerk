package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.Conflict;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.OccurrenceInput;
import club.badernet.nestwerk.calendar.TimeChange;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.web.EventForm;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read, create, change, move and delete events. */
@Component
public class CalendarTools {

    /** An event or occurrence; {@code categoryId}/{@code category} are empty without a category. */
    public record Event(Long eventId, String title, String start, String end, boolean allDay, String location,
                        String description, boolean recurring, String rrule, boolean privateEvent, boolean absence,
                        List<String> participants, List<String> warnings, String recurrenceId, Long categoryId,
                        String category, boolean noOverlap) {

        /** Notes of blockers of the participants (the event is saved nevertheless). */
        Event withWarnings(List<String> found) {
            return new Event(eventId, title, start, end, allDay, location, description, recurring, rrule,
                    privateEvent, absence, participants, found, recurrenceId, categoryId, category, noOverlap);
        }
    }

    /** Names of members and categories for the answers. */
    private record Names(Map<Long, String> members, Map<Long, String> categories) {
    }

    private static final int MAX_DAYS = 92;
    /** Passing this category id to update_event removes the category. */
    private static final long NO_CATEGORY = 0L;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Tools tools;
    private final CalendarEventService events;
    private final EventCategoryService categories;
    private final FamilyMemberRepository members;
    private final ZoneId zone;

    public CalendarTools(Tools tools, CalendarEventService events, EventCategoryService categories,
                         FamilyMemberRepository members, CalendarSettings settings) {
        this.tools = tools;
        this.events = events;
        this.categories = categories;
        this.members = members;
        this.zone = settings.zone();
    }

    ZoneId zone() {
        return zone;
    }

    @McpTool(name = "list_events", description = "Listet Termine (einzelne Vorkommen, Serien aufgelöst) im "
            + "Zeitraum von–bis, jeweils einschließlich. Ohne Angabe: heute bis in 7 Tagen. Höchstens 92 Tage.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Event> listEvents(McpTransportContext context,
                                  @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false) String from,
                                  @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false) String to) {
        return tools.read(context, user -> {
            LocalDate first = Tools.date(from, "from");
            if (first == null) {
                first = LocalDate.now(zone);
            }
            LocalDate last = Tools.date(to, "to");
            if (last == null) {
                last = first.plusDays(7);
            }
            if (last.isBefore(first) || last.isAfter(first.plusDays(MAX_DAYS))) {
                throw new Tools.ToolException("Der Zeitraum muss 0–" + MAX_DAYS + " Tage umfassen.");
            }
            Names names = names(user);
            return events.findOccurrences(first.atStartOfDay(zone).toInstant(),
                            last.plusDays(1).atStartOfDay(zone).toInstant(), user).stream()
                    .map(o -> view(o, names)).toList();
        });
    }

    @McpTool(name = "get_event", description = "Zeigt einen Termin mit allen Angaben, auch Wiederholungsregel und "
            + "Kategorie.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Event getEvent(McpTransportContext context,
                          @McpToolParam(description = "ID des Termins (eventId)") Long eventId) {
        return tools.read(context, user -> view(events.get(Tools.required(eventId, "eventId"), user), names(user)));
    }

    @McpTool(name = "create_event", description = "Legt einen Termin an. Mit Uhrzeit: startDate+startTime und "
            + "endTime (endDate optional, Standard = startDate). Ganztägig: allDay=true, endDate einschließlich. "
            + "Wiederholung als iCal-RRULE, z. B. FREQ=WEEKLY;BYDAY=MO. participantIds aus list_members, "
            + "categoryId aus list_event_categories (färbt den Termin). Arbeits-, Schlaf- und Ruhezeiten der "
            + "Teilnehmer: Hinweise stehen in warnings, gesperrte Zeiten lehnt Nestwerk mit Begründung ab.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Event createEvent(McpTransportContext context,
                             @McpToolParam(description = "Titel") String title,
                             @McpToolParam(description = "Erster Tag, JJJJ-MM-TT") String startDate,
                             @McpToolParam(description = "Beginn HH:MM (nicht bei ganztägig)", required = false)
                             String startTime,
                             @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false) String endDate,
                             @McpToolParam(description = "Ende HH:MM (nicht bei ganztägig)", required = false)
                             String endTime,
                             @McpToolParam(description = "Ganztägig", required = false) Boolean allDay,
                             @McpToolParam(description = "Ort", required = false) String location,
                             @McpToolParam(description = "Beschreibung", required = false) String description,
                             @McpToolParam(description = "Wiederholung als RRULE", required = false) String rrule,
                             @McpToolParam(description = "Nur für Teilnehmer sichtbar", required = false)
                             Boolean privateEvent,
                             @McpToolParam(description = "Teilnehmer sind abwesend (Haushaltsplan überspringt sie)",
                                     required = false) Boolean absence,
                             @McpToolParam(description = "IDs der teilnehmenden Familienmitglieder", required = false)
                             List<Long> participantIds,
                             @McpToolParam(description = "ID der Terminkategorie aus list_event_categories",
                                     required = false) Long categoryId,
                             @McpToolParam(description = "true = Hausarbeiten werden nicht über den Termin gelegt "
                                     + "(bei Serien für alle Vorkommen)", required = false) Boolean noOverlap) {
        return tools.write(context, user -> {
            EventForm form = EventForm.forNew(Tools.required(Tools.date(startDate, "startDate"), "startDate"));
            form.setTitle(title);
            apply(form, null, startTime, endDate, endTime, allDay, location, description, rrule, privateEvent,
                    absence, participantIds);
            form.setCategoryId(categoryId);
            form.setNoOverlap(Boolean.TRUE.equals(noOverlap));
            if (endDate == null || endDate.isBlank()) {
                form.setEndDate(form.getStartDate());
            }
            if (!form.isAllDay() && (startTime == null || endTime == null)) {
                throw new Tools.ToolException("Bei Terminen mit Uhrzeit sind startTime und endTime nötig.");
            }
            List<String> warnings = warnings(events.conflicts(form.toInput(), null, user));
            return view(events.create(form.toInput(), user), names(user)).withWarnings(warnings);
        });
    }

    @McpTool(name = "update_event", description = "Ändert einen Termin (bei Serien die ganze Serie). Nur "
            + "angegebene Felder werden geändert; participantIds ersetzt die Teilnehmerliste; categoryId 0 entfernt "
            + "die Kategorie.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Event updateEvent(McpTransportContext context,
                             @McpToolParam(description = "ID des Termins") Long eventId,
                             @McpToolParam(description = "Titel", required = false) String title,
                             @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false) String startDate,
                             @McpToolParam(description = "Beginn HH:MM", required = false) String startTime,
                             @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false) String endDate,
                             @McpToolParam(description = "Ende HH:MM", required = false) String endTime,
                             @McpToolParam(description = "Ganztägig", required = false) Boolean allDay,
                             @McpToolParam(description = "Ort", required = false) String location,
                             @McpToolParam(description = "Beschreibung", required = false) String description,
                             @McpToolParam(description = "Wiederholung als RRULE, leerer Text entfernt sie",
                                     required = false) String rrule,
                             @McpToolParam(description = "Nur für Teilnehmer sichtbar", required = false)
                             Boolean privateEvent,
                             @McpToolParam(description = "Teilnehmer sind abwesend", required = false) Boolean absence,
                             @McpToolParam(description = "IDs der Teilnehmer (ersetzt die Liste)", required = false)
                             List<Long> participantIds,
                             @McpToolParam(description = "ID der Terminkategorie aus list_event_categories, 0 = "
                                     + "keine Kategorie", required = false) Long categoryId,
                             @McpToolParam(description = "true = Hausarbeiten werden nicht über den Termin gelegt",
                                     required = false) Boolean noOverlap) {
        return tools.write(context, user -> {
            Long id = Tools.required(eventId, "eventId");
            EventForm form = EventForm.from(events.get(id, user));
            if (title != null) {
                form.setTitle(title);
            }
            apply(form, startDate, startTime, endDate, endTime, allDay, location, description, rrule, privateEvent,
                    absence, participantIds);
            if (categoryId != null) {
                form.setCategoryId(categoryId == NO_CATEGORY ? null : categoryId);
            }
            if (noOverlap != null) {
                form.setNoOverlap(noOverlap);
            }
            List<String> warnings = warnings(events.conflicts(form.toInput(), id, user));
            events.update(id, form.toInput(), user);
            return view(events.get(id, user), names(user)).withWarnings(warnings);
        });
    }

    @McpTool(name = "delete_event", description = "Löscht einen Termin endgültig, bei Serien die ganze Serie. Ein "
            + "einzelnes Vorkommen sagt cancel_event_occurrence ab. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteEvent(McpTransportContext context,
                              @McpToolParam(description = "ID des Termins (eventId)") Long eventId) {
        return tools.write(context, user -> {
            CalendarEvent event = events.get(Tools.required(eventId, "eventId"), user);
            String title = event.getTitle();
            events.delete(event.getId(), user);
            return "Termin „" + title + "“ gelöscht.";
        });
    }

    @McpTool(name = "move_event", description = "Verschiebt einen einzelnen (nicht wiederkehrenden) Termin auf einen "
            + "anderen Tag; Uhrzeit und Dauer bleiben. Hinweise von Blockern stehen in warnings.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Event moveEvent(McpTransportContext context,
                           @McpToolParam(description = "ID des Termins") Long eventId,
                           @McpToolParam(description = "Neuer Tag, JJJJ-MM-TT") String date) {
        return tools.write(context, user -> {
            Long id = Tools.required(eventId, "eventId");
            List<String> warnings = events.moveToDate(id, Tools.required(Tools.date(date, "date"), "date"), user)
                    .stream().map(Conflict::message).distinct().toList();
            return view(events.get(id, user), names(user)).withWarnings(warnings);
        });
    }

    @McpTool(name = "reschedule_event", description = "Gibt einem Termin neue Zeiten wie Ziehen oder Ändern der "
            + "Länge im Kalender: mit Uhrzeit start und end als JJJJ-MM-TTTHH:MM, ganztägig allDay=true mit "
            + "startDate und endDate (einschließlich). So wird ein Termin auch ganztägig oder bekommt eine Uhrzeit. "
            + "Bei Serien recurrenceId aus list_events angeben: dann ändert sich nur dieses Vorkommen. Hinweise von "
            + "Blockern stehen in warnings, gesperrte Zeiten werden abgelehnt.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Event rescheduleEvent(McpTransportContext context,
                                 @McpToolParam(description = "ID des Termins") Long eventId,
                                 @McpToolParam(description = "Beginn, JJJJ-MM-TTTHH:MM", required = false) String start,
                                 @McpToolParam(description = "Ende, JJJJ-MM-TTTHH:MM", required = false) String end,
                                 @McpToolParam(description = "Ganztägig", required = false) Boolean allDay,
                                 @McpToolParam(description = "Erster Tag bei ganztägig, JJJJ-MM-TT", required = false)
                                 String startDate,
                                 @McpToolParam(description = "Letzter Tag bei ganztägig, JJJJ-MM-TT", required = false)
                                 String endDate,
                                 @McpToolParam(description = "recurrenceId des Vorkommens einer Serie aus list_events",
                                         required = false) String recurrenceId) {
        return tools.write(context, user -> {
            Long id = Tools.required(eventId, "eventId");
            TimeChange change = Boolean.TRUE.equals(allDay)
                    ? new TimeChange.AllDay(Tools.required(Tools.date(startDate, "startDate"), "startDate"),
                    Tools.required(Tools.date(endDate, "endDate"), "endDate"))
                    : new TimeChange.Timed(dateTime(Tools.required(start, "start"), "start"),
                    dateTime(Tools.required(end, "end"), "end"));
            List<Conflict> found = recurrenceId == null || recurrenceId.isBlank()
                    ? events.reschedule(id, change, user)
                    : events.rescheduleOccurrence(id, instant(recurrenceId), change, user);
            return view(events.get(id, user), names(user)).withWarnings(warnings(found));
        });
    }

    @McpTool(name = "reschedule_event_series", description = "Verlegt eine Terminserie mit Uhrzeit auf eine andere "
            + "Uhrzeit und/oder Dauer; die Tage der Serie bleiben, abgesagte und geänderte Vorkommen ebenso. Endet "
            + "endTime vor startTime, geht der Termin über Mitternacht.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Event rescheduleEventSeries(McpTransportContext context,
                                       @McpToolParam(description = "ID der Serie") Long eventId,
                                       @McpToolParam(description = "Neuer Beginn HH:MM") String startTime,
                                       @McpToolParam(description = "Neues Ende HH:MM") String endTime) {
        return tools.write(context, user -> {
            Long id = Tools.required(eventId, "eventId");
            LocalTime from = Tools.required(Tools.time(startTime, "startTime"), "startTime");
            LocalTime to = Tools.required(Tools.time(endTime, "endTime"), "endTime");
            Duration length = Duration.between(from, to);
            if (!length.isPositive()) {
                length = length.plusDays(1);
            }
            List<Conflict> found = events.rescheduleSeries(id, from, length, user);
            return view(events.get(id, user), names(user)).withWarnings(warnings(found));
        });
    }

    @McpTool(name = "cancel_event_occurrence", description = "Sagt ein einzelnes Vorkommen einer Terminserie ab; die "
            + "Serie bleibt. recurrenceId steht bei den Vorkommen in list_events.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = true))
    public String cancelEventOccurrence(McpTransportContext context,
                                        @McpToolParam(description = "ID der Serie") Long eventId,
                                        @McpToolParam(description = "recurrenceId aus list_events") String recurrenceId) {
        return tools.write(context, user -> {
            events.cancelOccurrence(Tools.required(eventId, "eventId"), instant(recurrenceId), user);
            return "Vorkommen abgesagt.";
        });
    }

    @McpTool(name = "change_event_occurrence", description = "Ändert ein einzelnes Vorkommen einer Terminserie "
            + "(Titel, Ort, Beschreibung, Beginn, Ende als JJJJ-MM-TTTHH:MM). Nicht angegebene Felder bleiben wie im "
            + "Vorkommen. recurrenceId steht bei den Vorkommen in list_events.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String changeEventOccurrence(McpTransportContext context,
                                       @McpToolParam(description = "ID der Serie") Long eventId,
                                       @McpToolParam(description = "recurrenceId aus list_events") String recurrenceId,
                                       @McpToolParam(description = "Titel", required = false) String title,
                                       @McpToolParam(description = "Ort", required = false) String location,
                                       @McpToolParam(description = "Beschreibung", required = false) String description,
                                       @McpToolParam(description = "Beginn, JJJJ-MM-TTTHH:MM", required = false)
                                       String start,
                                       @McpToolParam(description = "Ende, JJJJ-MM-TTTHH:MM", required = false)
                                       String end) {
        return tools.write(context, user -> {
            Long id = Tools.required(eventId, "eventId");
            Instant at = instant(recurrenceId);
            Occurrence current = events.findOccurrences(at.minusSeconds(1), at.plusSeconds(1), user).stream()
                    .filter(o -> o.eventId().equals(id) && at.equals(o.recurrenceId())).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Dieses Vorkommen gibt es nicht."));
            events.overrideOccurrence(id, at, new OccurrenceInput(
                    title != null ? title : current.title(),
                    description != null ? description : current.description(),
                    location != null ? location : current.location(),
                    start != null ? dateTime(start, "start") : current.start().withZoneSameInstant(zone).toLocalDateTime(),
                    end != null ? dateTime(end, "end") : current.end().withZoneSameInstant(zone).toLocalDateTime()),
                    user);
            return "Vorkommen geändert.";
        });
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(Tools.required(value, "recurrenceId").trim());
        } catch (DateTimeParseException e) {
            throw new Tools.ToolException("recurrenceId: Wert aus list_events erwartet, nicht „" + value + "“.");
        }
    }

    private static LocalDateTime dateTime(String value, String name) {
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new Tools.ToolException(name + ": Format JJJJ-MM-TTTHH:MM erwartet, nicht „" + value + "“.");
        }
    }

    private static void apply(EventForm form, String startDate, String startTime, String endDate, String endTime,
                              Boolean allDay, String location, String description, String rrule,
                              Boolean privateEvent, Boolean absence, Collection<Long> participantIds) {
        if (allDay != null) {
            form.setAllDay(allDay);
            if (allDay) {
                form.setStartTime(null);
                form.setEndTime(null);
            }
        }
        LocalDate start = Tools.date(startDate, "startDate");
        if (start != null) {
            form.setStartDate(start);
        }
        LocalTime startAt = Tools.time(startTime, "startTime");
        if (startAt != null) {
            form.setStartTime(startAt);
        }
        LocalDate end = Tools.date(endDate, "endDate");
        if (end != null) {
            form.setEndDate(end);
        }
        LocalTime endAt = Tools.time(endTime, "endTime");
        if (endAt != null) {
            form.setEndTime(endAt);
        }
        if (location != null) {
            form.setLocation(location);
        }
        if (description != null) {
            form.setDescription(description);
        }
        if (rrule != null) {
            form.setRrule(rrule.isBlank() ? null : rrule.trim());
        }
        if (privateEvent != null) {
            form.setPrivateEvent(privateEvent);
        }
        if (absence != null) {
            form.setAbsence(absence);
        }
        if (participantIds != null) {
            form.setMemberIds(new HashSet<>(participantIds));
        }
    }

    private static List<String> warnings(List<Conflict> found) {
        return found.stream().filter(c -> c.strength() == BlockerStrength.WARN).map(Conflict::message).distinct()
                .toList();
    }

    private Names names(User user) {
        return new Names(members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName)),
                categories.list(user).stream().collect(Collectors.toMap(EventCategory::getId, EventCategory::getName)));
    }

    private static List<String> participants(Collection<Long> ids, Map<Long, String> names) {
        return ids.stream().map(id -> names.getOrDefault(id, "#" + id)).sorted().toList();
    }

    private Event view(Occurrence o, Names names) {
        // all-day: the end is stored exclusive, the last day is returned
        return new Event(o.eventId(), o.title(), format(o.start(), o.allDay()),
                format(o.allDay() ? o.end().minusDays(1) : o.end(), o.allDay()),
                o.allDay(), o.location(), o.description(), o.recurring(), null, o.privateEvent(), false,
                participants(o.memberIds(), names.members()), List.of(),
                o.recurring() ? String.valueOf(o.recurrenceId()) : null,
                o.categoryId(), o.categoryId() == null ? null : names.categories().get(o.categoryId()),
                o.noOverlap());
    }

    private Event view(CalendarEvent e, Names names) {
        ZoneId eventZone = ZoneId.of(e.getTimezone());
        ZonedDateTime start = e.getStartsAt().atZone(eventZone);
        ZonedDateTime end = e.getEndsAt().atZone(eventZone);
        Long categoryId = e.getCategoryId().orElse(null);
        return new Event(e.getId(), e.getTitle(), format(start, e.isAllDay()),
                format(e.isAllDay() ? end.minusDays(1) : end, e.isAllDay()), e.isAllDay(), e.getLocation(),
                e.getDescription(), e.getRrule() != null, e.getRrule(), e.isPrivateEvent(), e.isAbsence(),
                participants(e.getMemberIds(), names.members()), List.of(), null, categoryId,
                categoryId == null ? null : names.categories().get(categoryId), e.isNoOverlap());
    }

    private String format(ZonedDateTime value, boolean allDay) {
        ZonedDateTime local = value.withZoneSameInstant(zone);
        return allDay ? value.toLocalDate().toString() : local.toLocalDateTime().format(LOCAL);
    }
}
