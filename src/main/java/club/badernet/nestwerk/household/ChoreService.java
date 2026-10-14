package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import net.fortuna.ical4j.model.Recur;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Hausarbeiten (Vorlagen) anlegen, ändern, deaktivieren und ihre festen Termine pflegen. */
@Service
public class ChoreService {

    public static final int MAX_MINUTES = 600;

    private final ChoreRepository chores;
    private final ChoreDateRepository dates;
    private final ChoreTaskRepository tasks;
    private final ChoreCreditRepository credits;
    private final ChorePlanner planner;
    private final HouseholdAccess access;
    private final FamilyMemberRepository members;

    public ChoreService(ChoreRepository chores, ChoreDateRepository dates, ChoreTaskRepository tasks,
                        ChoreCreditRepository credits, ChorePlanner planner, HouseholdAccess access,
                        FamilyMemberRepository members) {
        this.chores = chores;
        this.dates = dates;
        this.tasks = tasks;
        this.credits = credits;
        this.planner = planner;
        this.access = access;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public List<Chore> visible(User viewer) {
        return chores.findAll().stream()
                .filter(c -> access.can(viewer, "VIEW", c))
                .sorted(Comparator.comparing(Chore::isActive).reversed()
                        .thenComparing(c -> c.getArea() == null ? "￿" : c.getArea().toLowerCase(Locale.GERMAN))
                        .thenComparing(c -> c.getTitle().toLowerCase(Locale.GERMAN)))
                .toList();
    }

    /** Liefert die Hausarbeit, wenn der Betrachter sie sehen darf; sonst wie „nicht vorhanden“. */
    @Transactional(readOnly = true)
    public Chore get(Long id, User viewer) {
        Chore chore = chores.findById(id).orElseThrow(ChoreNotFoundException::new);
        if (!access.can(viewer, "VIEW", chore)) {
            throw new ChoreNotFoundException();
        }
        return chore;
    }

    public boolean canCreate(User user) {
        return access.canAny(user, "CREATE");
    }

    @Transactional
    public Chore create(ChoreInput input, User actor) {
        if (!canCreate(actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von Hausarbeiten");
        }
        ChoreInput clean = validate(input);
        Chore chore = new Chore(clean.title(), actor.getId());
        chore.apply(clean);
        chore = chores.saveAndFlush(chore);
        planner.plan(chore, planner.today());
        return chore;
    }

    @Transactional
    public void update(Long id, ChoreInput input, User actor) {
        Chore chore = editable(id, actor);
        ChoreInput clean = validate(input);
        chore.apply(clean);
        chores.flush();
        planner.replan(chore, planner.today());
    }

    @Transactional
    public void deactivate(Long id, User actor) {
        Chore chore = deletable(id, actor);
        chore.setActive(false);
        planner.removeFutureOpen(chore, planner.today());
    }

    @Transactional
    public void activate(Long id, User actor) {
        Chore chore = deletable(id, actor);
        chore.setActive(true);
        chores.flush();
        planner.plan(chore, planner.today());
    }

    @Transactional
    public void delete(Long id, User actor) {
        Chore chore = deletable(id, actor);
        if (credits.existsByTaskChoreId(id)) {
            throw new IllegalArgumentException(
                    "Für diese Hausarbeit gibt es schon Gutschriften. Du kannst sie deaktivieren, aber nicht löschen.");
        }
        chores.delete(chore);
    }

    @Transactional(readOnly = true)
    public List<ChoreDate> dates(Long choreId) {
        return dates.findAllByChoreIdOrderByDueOnAscDueTimeAsc(choreId);
    }

    @Transactional
    public ChoreDate addDate(Long choreId, LocalDate day, LocalTime time, User actor) {
        Chore chore = editable(choreId, actor);
        if (chore.getKind() != ChoreKind.DATES) {
            throw new IllegalArgumentException("Feste Termine gibt es nur bei Hausarbeiten mit festen Terminen.");
        }
        if (day == null) {
            throw new IllegalArgumentException("Bitte ein Datum angeben.");
        }
        String key = ChoreTask.slotKey(day, time);
        if (dates(choreId).stream().anyMatch(d -> d.slotKey().equals(key))) {
            throw new IllegalArgumentException("Diesen Termin gibt es schon.");
        }
        ChoreDate saved = dates.saveAndFlush(new ChoreDate(choreId, day, time, ChoreDateSource.MANUAL, null));
        planner.plan(chore, planner.today());
        return saved;
    }

    @Transactional
    public void removeDate(Long choreId, Long dateId, User actor) {
        Chore chore = editable(choreId, actor);
        ChoreDate date = dates.findById(dateId).filter(d -> d.getChoreId().equals(choreId))
                .orElseThrow(ChoreNotFoundException::new);
        removeOpenTask(chore, date.slotKey());
        dates.delete(date);
    }

    /** Entfernt die offene Aufgabe eines Termins (ab heute); erledigte bleiben. */
    void removeOpenTask(Chore chore, String slotKey) {
        tasks.findByChoreIdAndSlotKey(chore.getId(), slotKey)
                .filter(t -> t.isOpen() && !t.getDueOn().isBefore(planner.today()))
                .ifPresent(tasks::delete);
    }

    Chore editable(Long id, User actor) {
        Chore chore = get(id, actor);
        if (!access.can(actor, "EDIT", chore)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieser Hausarbeit");
        }
        return chore;
    }

    private Chore deletable(Long id, User actor) {
        Chore chore = get(id, actor);
        if (!access.can(actor, "DELETE", chore)) {
            throw new AccessDeniedException("Keine Berechtigung zum Löschen dieser Hausarbeit");
        }
        return chore;
    }

    private ChoreInput validate(ChoreInput in) {
        String title = clean(in.title());
        if (title == null || title.length() > 150) {
            throw new IllegalArgumentException("Der Titel muss 1–150 Zeichen lang sein.");
        }
        String area = clean(in.area());
        if (area != null && area.length() > 100) {
            throw new IllegalArgumentException("Der Bereich darf höchstens 100 Zeichen lang sein.");
        }
        String description = clean(in.description());
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException("Die Beschreibung darf höchstens 2000 Zeichen lang sein.");
        }
        if (in.minutes() == null || in.minutes() < 1 || in.minutes() > MAX_MINUTES) {
            throw new IllegalArgumentException("Die Minuten müssen zwischen 1 und " + MAX_MINUTES + " liegen.");
        }
        if (in.effort() == null) {
            throw new IllegalArgumentException("Bitte einen Aufwand wählen.");
        }
        if (in.kind() == null) {
            throw new IllegalArgumentException("Bitte wählen, wann die Hausarbeit fällig wird.");
        }
        if (in.distribution() == null) {
            throw new IllegalArgumentException("Bitte eine Verteilung wählen.");
        }
        String rrule = null;
        Integer interval = null;
        LocalDate startsOn = in.startsOn();
        LocalTime time = in.dueTime();
        switch (in.kind()) {
            case RECURRING -> {
                rrule = normalizeRule(in.rrule(), startsOn != null ? startsOn : LocalDate.now());
                if (startsOn == null) {
                    throw new IllegalArgumentException("Bitte ein Startdatum angeben.");
                }
            }
            case INTERVAL -> {
                if (in.intervalDays() == null || in.intervalDays() < 1 || in.intervalDays() > 365) {
                    throw new IllegalArgumentException("Der Abstand muss zwischen 1 und 365 Tagen liegen.");
                }
                interval = in.intervalDays();
            }
            case DATES -> {
                startsOn = null;
                time = null;
            }
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(in.memberIds() == null ? List.of() : in.memberIds()));
        if (!ids.isEmpty() && members.findAllById(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
        if (in.distribution() == Distribution.FIXED && ids.size() != 1) {
            throw new IllegalArgumentException("Bei fester Zuweisung bitte genau ein Mitglied auswählen.");
        }
        if ((in.distribution() == Distribution.ROTATION || in.distribution() == Distribution.BALANCED) && ids.isEmpty()) {
            throw new IllegalArgumentException("Bitte mindestens ein Mitglied auswählen.");
        }
        return new ChoreInput(title, area, description, in.minutes(), in.effort(), in.kind(), rrule, startsOn, time,
                interval, in.distribution(), ids);
    }

    private static String normalizeRule(String rrule, LocalDate seed) {
        String rule = clean(rrule);
        if (rule == null) {
            throw new IllegalArgumentException("Bitte angeben, wie oft die Hausarbeit fällig ist.");
        }
        rule = rule.replaceFirst("(?i)^RRULE:", "");
        if (rule.length() > 500) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist zu lang.");
        }
        try {
            new Recur<LocalDate>(rule).getDates(seed, seed, seed.plusYears(1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist ungültig.", e);
        }
        return rule;
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
