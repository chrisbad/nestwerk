package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Schulferien je Bundesland: Abruf aus der Online-Quelle, manuelle Ergänzungen und Abfrage. */
@Service
public class HolidayService {

    /** Wie weit der Abruf zurück- und vorausschaut. */
    static final int PAST_MONTHS = 1;
    static final int FUTURE_YEARS = 2;

    private record Key(String name, LocalDate start, LocalDate end) {
    }

    private final SchoolHolidayRepository holidays;
    private final HolidayClient client;
    private final SchoolBlockerSync sync;
    private final SchoolAccess access;

    public HolidayService(SchoolHolidayRepository holidays, HolidayClient client, SchoolBlockerSync sync,
                          SchoolAccess access) {
        this.holidays = holidays;
        this.client = client;
        this.sync = sync;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<SchoolHoliday> list(String state, LocalDate from, LocalDate to) {
        return holidays.findOverlapping(state, from, to);
    }

    /**
     * Holt die Ferien des Bundeslands neu und ersetzt die bisherigen Online-Einträge, wenn sie sich geändert haben.
     * Manuelle Einträge bleiben. Liefert, ob sich etwas geändert hat; die Schul-Blocker werden dann abgeglichen.
     */
    @Transactional
    public boolean refresh(String state, LocalDate today) {
        LocalDate from = today.minusMonths(PAST_MONTHS);
        List<HolidayClient.Entry> fetched = client.fetch(state, from, today.plusYears(FUTURE_YEARS));
        Set<Key> before = new HashSet<>();
        holidays.findOverlapping(state, from, from.plusYears(50)).stream()
                .filter(h -> SchoolHoliday.ONLINE.equals(h.getSource()))
                .forEach(h -> before.add(new Key(h.getName(), h.getStartsOn(), h.getEndsOn())));
        Set<Key> after = new HashSet<>();
        fetched.forEach(e -> after.add(new Key(e.name(), e.startsOn(), e.endsOn())));
        if (before.equals(after)) {
            return false;
        }
        holidays.deleteOnlineFrom(state, from);
        holidays.flush();
        for (HolidayClient.Entry e : fetched) {
            holidays.save(new SchoolHoliday(state, e.name(), e.startsOn(), e.endsOn(), SchoolHoliday.ONLINE,
                    e.externalId() == null ? null : clip(e.externalId())));
        }
        holidays.flush();
        sync.syncAllActive();
        return true;
    }

    @Transactional
    public SchoolHoliday addManual(String state, String name, LocalDate startsOn, LocalDate endsOn, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_SETTINGS);
        if (state == null || !GermanHolidays.STATES.containsKey(state)) {
            throw new IllegalArgumentException("Bitte ein Bundesland wählen.");
        }
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 100) {
            throw new IllegalArgumentException("Der Name muss 1–100 Zeichen lang sein.");
        }
        if (startsOn == null || endsOn == null || endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("Bitte Beginn und Ende angeben; das Ende darf nicht vor dem Beginn liegen.");
        }
        SchoolHoliday saved = holidays.save(new SchoolHoliday(state, cleanName, startsOn, endsOn,
                SchoolHoliday.MANUAL, null));
        holidays.flush();
        sync.syncAllActive();
        return saved;
    }

    @Transactional
    public void deleteManual(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_SETTINGS);
        SchoolHoliday holiday = holidays.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Eintrag nicht gefunden"));
        if (!SchoolHoliday.MANUAL.equals(holiday.getSource())) {
            throw new IllegalArgumentException("Nur selbst eingetragene Ferien lassen sich löschen.");
        }
        holidays.delete(holiday);
        holidays.flush();
        sync.syncAllActive();
    }

    private static String clip(String value) {
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
