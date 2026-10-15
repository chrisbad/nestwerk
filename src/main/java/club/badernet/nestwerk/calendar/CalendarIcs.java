package club.badernet.nestwerk.calendar;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.util.CompatibilityHints;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Termine als iCalendar (RFC 5545): Schreiben von Nestwerk-Terminen mit Serien und Ausnahmen, Lesen fremder
 * Kalenderdateien (z. B. Google- oder Outlook-Export) in eine einfache Form.
 */
public final class CalendarIcs {

    /** Ein eingelesener Termin. Ganztägig: {@code endDate} einschließlich. */
    public record Parsed(String uid, String title, String description, String location, LocalDate startDate,
                         ZonedDateTime start, LocalDate endDate, ZonedDateTime end, boolean allDay, String rrule,
                         boolean privateEvent) {
    }

    /** Ergebnis des Einlesens: Termine und übersprungene Einträge mit Grund. */
    public record ParseResult(List<Parsed> events, List<String> skipped) {
    }

    public static final int MAX_EVENTS = 5000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    static {
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
    }

    private CalendarIcs() {
    }

    // --- Schreiben -------------------------------------------------------------------

    public static String write(Collection<CalendarEvent> events, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Nestwerk//Kalender//DE");
        line(out, "CALSCALE:GREGORIAN");
        // die VTIMEZONE-Definition liefert ical4j; Zeilen sind bereits gefaltet und mit CRLF getrennt
        out.append(TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone(zone.getId()).getVTimeZone()
                .toString().replace("\r\n", "\n").replace("\n", "\r\n"));
        for (CalendarEvent e : events) {
            ZoneId eventZone = ZoneId.of(e.getTimezone());
            line(out, "BEGIN:VEVENT");
            text(out, "UID", e.getUid());
            line(out, "DTSTAMP:" + utc(e.getUpdatedAt() == null ? Instant.now() : e.getUpdatedAt()));
            times(out, e.getStartsAt(), e.getEndsAt(), e.isAllDay(), eventZone);
            text(out, "SUMMARY", e.getTitle());
            text(out, "DESCRIPTION", e.getDescription());
            text(out, "LOCATION", e.getLocation());
            if (e.isPrivateEvent()) {
                line(out, "CLASS:PRIVATE");
            }
            if (e.getRrule() != null) {
                line(out, "RRULE:" + e.getRrule());
                for (CalendarEventException x : e.getExceptions()) {
                    if (x.isCancelled()) {
                        line(out, "EXDATE" + reference(x.getRecurrenceId(), e.isAllDay(), eventZone));
                    }
                }
            }
            line(out, "SEQUENCE:" + e.getSequence());
            line(out, "END:VEVENT");
            if (e.getRrule() != null) {
                for (CalendarEventException x : e.getExceptions()) {
                    if (!x.isCancelled()) {
                        line(out, "BEGIN:VEVENT");
                        text(out, "UID", e.getUid());
                        line(out, "DTSTAMP:" + utc(e.getUpdatedAt() == null ? Instant.now() : e.getUpdatedAt()));
                        line(out, "RECURRENCE-ID" + reference(x.getRecurrenceId(), e.isAllDay(), eventZone));
                        Instant start = x.getStartsAt() != null ? x.getStartsAt() : x.getRecurrenceId();
                        Instant end = x.getEndsAt() != null ? x.getEndsAt()
                                : start.plus(Duration.between(e.getStartsAt(), e.getEndsAt()));
                        times(out, start, end, e.isAllDay(), eventZone);
                        text(out, "SUMMARY", x.getTitle() != null ? x.getTitle() : e.getTitle());
                        text(out, "DESCRIPTION", x.getDescription() != null ? x.getDescription() : e.getDescription());
                        text(out, "LOCATION", x.getLocation() != null ? x.getLocation() : e.getLocation());
                        line(out, "END:VEVENT");
                    }
                }
            }
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    private static void times(StringBuilder out, Instant start, Instant end, boolean allDay, ZoneId zone) {
        if (allDay) {
            line(out, "DTSTART;VALUE=DATE:" + start.atZone(zone).toLocalDate().format(DATE));
            line(out, "DTEND;VALUE=DATE:" + end.atZone(zone).toLocalDate().format(DATE));
        } else {
            line(out, "DTSTART;TZID=" + zone.getId() + ":" + start.atZone(zone).toLocalDateTime().format(LOCAL));
            line(out, "DTEND;TZID=" + zone.getId() + ":" + end.atZone(zone).toLocalDateTime().format(LOCAL));
        }
    }

    private static String reference(Instant recurrenceId, boolean allDay, ZoneId zone) {
        return allDay ? ";VALUE=DATE:" + recurrenceId.atZone(zone).toLocalDate().format(DATE)
                : ";TZID=" + zone.getId() + ":" + recurrenceId.atZone(zone).toLocalDateTime().format(LOCAL);
    }

    private static String utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime().withNano(0).format(LOCAL) + "Z";
    }

    private static void text(StringBuilder out, String name, String value) {
        if (value != null && !value.isBlank()) {
            line(out, name + ":" + value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                    .replace("\r\n", "\n").replace("\n", "\\n"));
        }
    }

    /** Schreibt eine Zeile, gefaltet nach 75 Oktetten (UTF-8) wie in RFC 5545 vorgeschrieben. */
    private static void line(StringBuilder out, String content) {
        int bytes = 0;
        int limit = 75;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > limit) {
                out.append("\r\n ");
                bytes = 1;
                limit = 75;
            }
            out.appendCodePoint(cp);
            bytes += size;
            i += Character.charCount(cp);
        }
        out.append("\r\n");
    }

    // --- Lesen -----------------------------------------------------------------------

    public static ParseResult parse(byte[] data, ZoneId zone) {
        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new InputStreamReader(new ByteArrayInputStream(data),
                    StandardCharsets.UTF_8));
        } catch (IOException | ParserException | RuntimeException e) {
            throw new IllegalArgumentException("Die Datei ist kein gültiger iCal-Kalender.");
        }
        List<Parsed> events = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int index = 0;
        for (CalendarComponent component : calendar.getComponents()) {
            if (!(component instanceof VEvent event)) {
                continue;
            }
            index++;
            String title = value(event, Property.SUMMARY).orElse("(ohne Titel)");
            if (value(event, Property.RECURRENCE_ID).isPresent()) {
                skipped.add(title + ": geänderter Einzeltermin einer Serie wird nicht übernommen");
                continue;
            }
            if (events.size() >= MAX_EVENTS) {
                throw new IllegalArgumentException("Die Datei enthält mehr als " + MAX_EVENTS + " Termine.");
            }
            try {
                events.add(toParsed(event, title, index, zone));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                skipped.add(title + ": " + e.getMessage());
            }
        }
        return new ParseResult(events, skipped);
    }

    private static Parsed toParsed(VEvent event, String title, int index, ZoneId zone) {
        Property startProp = event.getPropertyList().getProperty(Property.DTSTART)
                .orElseThrow(() -> new IllegalArgumentException("Beginn fehlt"));
        Object start = time(startProp, zone);
        Optional<Property> endProp = event.getPropertyList().getProperty(Property.DTEND);
        String uid = value(event, Property.UID).orElse("import-" + index + "-" + startProp.getValue());
        if (uid.length() > 255) {
            throw new IllegalArgumentException("UID zu lang");
        }
        String rrule = value(event, Property.RRULE).orElse(null);
        boolean privateEvent = value(event, Property.CLASS).map(v -> !v.equalsIgnoreCase("PUBLIC")).orElse(false);
        String description = value(event, Property.DESCRIPTION).orElse(null);
        String location = value(event, Property.LOCATION).orElse(null);
        if (start instanceof LocalDate day) {
            LocalDate endDay = endProp.map(p -> time(p, zone)).filter(LocalDate.class::isInstance)
                    .map(LocalDate.class::cast).map(d -> d.minusDays(1)).orElse(day);
            if (endDay.isBefore(day)) {
                endDay = day;
            }
            return new Parsed(uid, title, description, location, day, null, endDay, null, true, rrule, privateEvent);
        }
        ZonedDateTime begin = (ZonedDateTime) start;
        ZonedDateTime finish = endProp.map(p -> time(p, zone)).filter(ZonedDateTime.class::isInstance)
                .map(ZonedDateTime.class::cast)
                .or(() -> value(event, Property.DURATION).map(d -> begin.plus(Duration.parse(d))))
                .orElse(begin.plusHours(1));
        if (!finish.isAfter(begin)) {
            finish = begin.plusMinutes(1);
        }
        return new Parsed(uid, title, description, location, null, begin.withZoneSameInstant(zone), null,
                finish.withZoneSameInstant(zone), false, rrule, privateEvent);
    }

    /** {@link LocalDate} bei ganztägig, sonst {@link ZonedDateTime} (UTC, TZID oder ohne Zone = App-Zone). */
    private static Object time(Property p, ZoneId zone) {
        String v = p.getValue().trim();
        if (v.length() == 8) {
            return LocalDate.parse(v, DATE);
        }
        if (v.length() < 15) {
            throw new IllegalArgumentException("Zeitangabe „" + v + "“ unverständlich");
        }
        LocalDateTime local = LocalDateTime.parse(v.substring(0, 15), LOCAL);
        if (v.endsWith("Z")) {
            return local.atZone(ZoneOffset.UTC);
        }
        ZoneId tz = p.<Parameter>getParameter(Parameter.TZID).map(Parameter::getValue).flatMap(CalendarIcs::zone)
                .orElse(zone);
        return local.atZone(tz);
    }

    private static Optional<ZoneId> zone(String id) {
        try {
            return Optional.of(ZoneId.of(id.replace("\"", "")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> value(VEvent event, String name) {
        return event.getPropertyList().getProperty(name).map(Property::getValue).filter(v -> !v.isBlank());
    }
}
