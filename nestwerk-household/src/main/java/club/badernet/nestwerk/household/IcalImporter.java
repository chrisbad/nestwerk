package club.badernet.nestwerk.household;

import club.badernet.nestwerk.net.IcalFetcher;
import club.badernet.nestwerk.user.User;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.util.CompatibilityHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Übernimmt feste Termine aus einer iCal-Datei oder einem Abo (z. B. Abfuhrkalender). Abgleich über die UID:
 * neue Termine kommen hinzu, verschobene werden verschoben, verschwundene künftige iCal-Termine fallen weg.
 * Von Hand eingetragene Termine bleiben unberührt.
 */
@Service
public class IcalImporter {

    /** Zusammenfassung eines Imports. */
    public record Result(int added, int moved, int removed, int total) {
    }

    static final int MAX_ENTRIES = 1000;
    static final int LOOKAHEAD_DAYS = 400;
    private static final Logger log = LoggerFactory.getLogger(IcalImporter.class);
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    static {
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
    }

    private final ChoreService chores;
    private final ChoreDateRepository dates;
    private final ChorePlanner planner;
    private final IcalFetcher fetcher;
    private final ChoreRepository choreRepository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public IcalImporter(ChoreService chores, ChoreDateRepository dates, ChorePlanner planner, IcalFetcher fetcher,
                        ChoreRepository choreRepository, PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.chores = chores;
        this.dates = dates;
        this.planner = planner;
        this.fetcher = fetcher;
        this.choreRepository = choreRepository;
        this.clock = clock;
    }

    /** Import aus einer hochgeladenen Datei (ohne Abo). */
    @Transactional
    public Result importFile(Long choreId, byte[] data, String filter, int daysBefore, LocalTime time, User actor) {
        Chore chore = prepare(choreId, filter, daysBefore, actor);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Bitte eine iCal-Datei (.ics) auswählen.");
        }
        if (data.length > IcalFetcher.MAX_BYTES) {
            throw new IllegalArgumentException("Die Datei ist größer als 1 MB.");
        }
        chore.configureIcal(null, cleanFilter(filter), daysBefore, time);
        return apply(chore, data);
    }

    /** Abo einrichten und sofort laden; danach wird es täglich aktualisiert. */
    @Transactional
    public Result subscribe(Long choreId, String url, String filter, int daysBefore, LocalTime time, User actor) {
        Chore chore = prepare(choreId, filter, daysBefore, actor);
        String clean = fetcher.validate(url).toString();
        byte[] data = fetcher.fetch(clean);
        chore.configureIcal(clean, cleanFilter(filter), daysBefore, time);
        return apply(chore, data);
    }

    @Transactional
    public void unsubscribe(Long choreId, User actor) {
        Chore chore = chores.editable(choreId, actor);
        chore.configureIcal(null, chore.getIcalFilter(), chore.getIcalDaysBefore(), chore.getIcalTime());
    }

    /** Tägliche Aktualisierung aller Abos, jedes in eigener Transaktion; Fehler werden protokolliert. */
    public void syncAll() {
        List<Long> ids = transactions.execute(status -> choreRepository.findAllByIcalUrlIsNotNullAndActiveTrue()
                .stream().map(Chore::getId).toList());
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                transactions.executeWithoutResult(status -> choreRepository.findById(id)
                        .ifPresent(chore -> apply(chore, fetcher.fetch(chore.getIcalUrl()))));
            } catch (RuntimeException e) {
                log.warn("Kalender-Abo für Hausarbeit {} konnte nicht aktualisiert werden: {}", id, e.getMessage());
            }
        }
    }

    private Chore prepare(Long choreId, String filter, int daysBefore, User actor) {
        Chore chore = chores.editable(choreId, actor);
        if (chore.getKind() != ChoreKind.DATES) {
            throw new IllegalArgumentException("Import gibt es nur bei Hausarbeiten mit festen Terminen.");
        }
        if (daysBefore < 0 || daysBefore > 7) {
            throw new IllegalArgumentException("„Tage vorher“ muss zwischen 0 und 7 liegen.");
        }
        cleanFilter(filter);
        return chore;
    }

    private static String cleanFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String f = filter.trim();
        if (f.length() > 100) {
            throw new IllegalArgumentException("Der Filter darf höchstens 100 Zeichen lang sein.");
        }
        return f;
    }

    Result apply(Chore chore, byte[] data) {
        LocalDate today = LocalDate.now(clock);
        Map<String, LocalDate> wanted = parse(data, chore.getIcalFilter(), today);
        Map<String, ChoreDate> existing = new HashMap<>();
        for (ChoreDate d : dates.findAllByChoreIdOrderByDueOnAscDueTimeAsc(chore.getId())) {
            if (d.getSource() == ChoreDateSource.ICAL && d.getExternalUid() != null) {
                existing.put(d.getExternalUid(), d);
            }
        }
        int added = 0;
        int moved = 0;
        int removed = 0;
        LocalTime time = chore.getIcalTime();
        for (Map.Entry<String, LocalDate> e : wanted.entrySet()) {
            LocalDate due = e.getValue().minusDays(chore.getIcalDaysBefore());
            ChoreDate current = existing.remove(e.getKey());
            if (current == null) {
                dates.save(new ChoreDate(chore.getId(), due, time, ChoreDateSource.ICAL, e.getKey()));
                added++;
            } else if (!due.equals(current.getDueOn()) || !java.util.Objects.equals(time, current.getDueTime())) {
                chores.removeOpenTask(chore, current.slotKey());
                current.move(due, time);
                moved++;
            }
        }
        for (ChoreDate gone : existing.values()) {
            if (!gone.getDueOn().isBefore(today)) {
                chores.removeOpenTask(chore, gone.slotKey());
                dates.delete(gone);
                removed++;
            }
        }
        dates.flush();
        chore.markIcalSynced(clock.instant());
        planner.plan(chore, today);
        return new Result(added, moved, removed, wanted.size());
    }

    /** Termine (UID → Datum) ab heute, gefiltert nach Titel; Serien werden bis 400 Tage voraus aufgelöst. */
    Map<String, LocalDate> parse(byte[] data, String filter, LocalDate today) {
        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        } catch (IOException | ParserException | RuntimeException e) {
            throw new IllegalArgumentException("Die Datei ist kein gültiger iCal-Kalender.");
        }
        String needle = filter == null ? null : filter.toLowerCase(Locale.GERMAN);
        LocalDate until = today.plusDays(LOOKAHEAD_DAYS);
        Map<String, LocalDate> result = new LinkedHashMap<>();
        int index = 0;
        for (CalendarComponent component : calendar.getComponents()) {
            if (!(component instanceof VEvent event)) {
                continue;
            }
            index++;
            String summary = value(event, Property.SUMMARY).orElse("");
            if (needle != null && !summary.toLowerCase(Locale.GERMAN).contains(needle)) {
                continue;
            }
            Optional<LocalDate> start = value(event, Property.DTSTART).flatMap(IcalImporter::date);
            if (start.isEmpty()) {
                continue;
            }
            String uid = value(event, Property.UID).orElse("ohne-uid-" + index + "-" + start.get());
            if (value(event, Property.RECURRENCE_ID).isPresent()) {
                uid = uid + "#" + value(event, Property.RECURRENCE_ID).flatMap(IcalImporter::date).map(LocalDate::toString).orElse("");
            }
            Optional<String> rrule = value(event, Property.RRULE);
            if (rrule.isPresent()) {
                List<LocalDate> occurrences;
                try {
                    occurrences = new Recur<LocalDate>(rrule.get().replaceAll(";UNTIL=(\\d{8})T[0-9Z]+", ";UNTIL=$1"))
                            .getDates(start.get(), today, until);
                } catch (RuntimeException e) {
                    continue;
                }
                for (LocalDate d : occurrences) {
                    if (!d.isBefore(today) && !d.isAfter(until)) {
                        put(result, uid + "#" + d, d);
                    }
                }
            } else if (!start.get().isBefore(today)) {
                put(result, uid, start.get());
            }
        }
        return result;
    }

    private static void put(Map<String, LocalDate> result, String uid, LocalDate day) {
        if (result.size() >= MAX_ENTRIES && !result.containsKey(uid)) {
            throw new IllegalArgumentException("Der Kalender enthält mehr als " + MAX_ENTRIES + " passende Termine. "
                    + "Bitte einen Filter angeben.");
        }
        String key = uid.length() > 255 ? DeviceService.sha256(uid) : uid;
        result.put(key, day);
    }

    private static Optional<String> value(VEvent event, String name) {
        Optional<Property> property = event.getPropertyList().getProperty(name);
        return property.map(Property::getValue).filter(v -> !v.isBlank());
    }

    private static Optional<LocalDate> date(String value) {
        if (value.length() < 8) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.substring(0, 8), BASIC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
