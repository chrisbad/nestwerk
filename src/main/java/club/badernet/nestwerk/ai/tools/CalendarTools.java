package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.web.EventForm;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Termine lesen, anlegen und ändern. */
@Component
public class CalendarTools {

    public record Event(Long eventId, String title, String start, String end, boolean allDay, String location,
                        String description, boolean recurring, String rrule, boolean privateEvent, boolean absence,
                        List<String> participants) {
    }

    private static final int MAX_DAYS = 92;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Tools tools;
    private final CalendarEventService events;
    private final FamilyMemberRepository members;
    private final ZoneId zone;

    public CalendarTools(Tools tools, CalendarEventService events, FamilyMemberRepository members,
                         CalendarSettings settings) {
        this.tools = tools;
        this.events = events;
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
            Map<Long, String> names = names();
            return events.findOccurrences(first.atStartOfDay(zone).toInstant(),
                            last.plusDays(1).atStartOfDay(zone).toInstant(), user).stream()
                    .map(o -> view(o, names)).toList();
        });
    }

    @McpTool(name = "get_event", description = "Zeigt einen Termin mit allen Angaben, auch die Wiederholungsregel.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Event getEvent(McpTransportContext context,
                          @McpToolParam(description = "ID des Termins (eventId)") Long eventId) {
        return tools.read(context, user -> view(events.get(Tools.required(eventId, "eventId"), user), names()));
    }

    @McpTool(name = "create_event", description = "Legt einen Termin an. Mit Uhrzeit: startDate+startTime und "
            + "endTime (endDate optional, Standard = startDate). Ganztägig: allDay=true, endDate einschließlich. "
            + "Wiederholung als iCal-RRULE, z. B. FREQ=WEEKLY;BYDAY=MO. participantIds aus list_members.",
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
                             List<Long> participantIds) {
        return tools.write(context, user -> {
            EventForm form = EventForm.forNew(Tools.required(Tools.date(startDate, "startDate"), "startDate"));
            form.setTitle(title);
            apply(form, null, startTime, endDate, endTime, allDay, location, description, rrule, privateEvent,
                    absence, participantIds);
            if (endDate == null || endDate.isBlank()) {
                form.setEndDate(form.getStartDate());
            }
            if (!form.isAllDay() && (startTime == null || endTime == null)) {
                throw new Tools.ToolException("Bei Terminen mit Uhrzeit sind startTime und endTime nötig.");
            }
            return view(events.create(form.toInput(), user), names());
        });
    }

    @McpTool(name = "update_event", description = "Ändert einen Termin (bei Serien die ganze Serie). Nur "
            + "angegebene Felder werden geändert; participantIds ersetzt die Teilnehmerliste.",
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
                             List<Long> participantIds) {
        return tools.write(context, user -> {
            Long id = Tools.required(eventId, "eventId");
            EventForm form = EventForm.from(events.get(id, user));
            if (title != null) {
                form.setTitle(title);
            }
            apply(form, startDate, startTime, endDate, endTime, allDay, location, description, rrule, privateEvent,
                    absence, participantIds);
            events.update(id, form.toInput(), user);
            return view(events.get(id, user), names());
        });
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

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    private static List<String> participants(Collection<Long> ids, Map<Long, String> names) {
        return ids.stream().map(id -> names.getOrDefault(id, "#" + id)).sorted().toList();
    }

    private Event view(Occurrence o, Map<Long, String> names) {
        // ganztägig: Ende ist exklusiv gespeichert, ausgegeben wird der letzte Tag
        return new Event(o.eventId(), o.title(), format(o.start(), o.allDay()),
                format(o.allDay() ? o.end().minusDays(1) : o.end(), o.allDay()),
                o.allDay(), o.location(), o.description(), o.recurring(), null, o.privateEvent(), false,
                participants(o.memberIds(), names));
    }

    private Event view(CalendarEvent e, Map<Long, String> names) {
        ZoneId eventZone = ZoneId.of(e.getTimezone());
        ZonedDateTime start = e.getStartsAt().atZone(eventZone);
        ZonedDateTime end = e.getEndsAt().atZone(eventZone);
        return new Event(e.getId(), e.getTitle(), format(start, e.isAllDay()),
                format(e.isAllDay() ? end.minusDays(1) : end, e.isAllDay()), e.isAllDay(), e.getLocation(),
                e.getDescription(), e.getRrule() != null, e.getRrule(), e.isPrivateEvent(), e.isAbsence(),
                participants(e.getMemberIds(), names));
    }

    private String format(ZonedDateTime value, boolean allDay) {
        ZonedDateTime local = value.withZoneSameInstant(zone);
        return allDay ? value.toLocalDate().toString() : local.toLocalDateTime().format(LOCAL);
    }
}
