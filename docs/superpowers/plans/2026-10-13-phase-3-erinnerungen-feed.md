# Phase 3: Erinnerungen und iCal-Feed – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In-App-Erinnerungen vor Terminbeginn (mit Benachrichtigungsliste) und ein abonnierbarer, schreibgeschützter iCal-Feed je Benutzer.

**Architecture:** Erinnerungen sind Minuten-Offsets je Termin (`calendar_event_reminders`). Ein geplanter Job (`@Scheduled`, 60 s) erzeugt fällige `notifications` idempotent (Unique-Key je Benutzer/Termin/Vorkommen/Offset). Der Feed liefert die **nicht expandierten** Stammtermine samt RRULE, EXDATE und Überschreibungen über einen eigenen `ICalWriter` (kein ical4j-Builder), authentifiziert über einen widerrufbaren Token in der URL. `ICalWriter` wird in Phase 4 für CalDAV wiederverwendet.

**Tech Stack:** wie Phase 2 (ical4j nur für Expansion), `java.time.zone.ZoneRules` für VTIMEZONE.

**Spec:** `docs/superpowers/specs/2026-10-13-basis-rollen-mitglieder-design.md`
**Voraussetzung:** Phase 1 und 2 umgesetzt.

## Global Constraints

- Erinnerungen erscheinen nur in der App (keine E-Mail, kein Push).
- Private Termine lösen Erinnerungen und Feed-Einträge ausschließlich für Ersteller/Administrator aus; zugewiesene Mitglieder anderer Benutzer erfahren nichts davon.
- Der Feed enthält nur Termine, die der Token-Besitzer in der Web-UI sehen dürfte; Token ist widerrufbar und wird nur als SHA-256-Hash gespeichert (Klartext wird einmalig angezeigt).
- iCal-Ausgabe: CRLF-Zeilenenden, Zeilenfaltung bei 75 Oktetten (UTF-8), Text-Escaping nach RFC 5545, VTIMEZONE für jede verwendete Zeitzone; ganztägige Termine als `VALUE=DATE` mit exklusivem Ende.
- Flyway: neue Version `V4__reminders_and_feed.sql`.
- Erlaubte Erinnerungs-Offsets (Minuten): 0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080.
- Commit-Nachrichten enden mit `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

- Erinnerung wird nach Neustart/zweitem Joblauf nicht doppelt erzeugt.
- Abgesagtes Vorkommen einer Serie löst keine Erinnerung aus; verschobenes Vorkommen erinnert zur neuen Zeit.
- Termin liegt bereits in der Vergangenheit (älter als 15 Minuten): keine nachträgliche Flut an Erinnerungen beim ersten Joblauf.
- Benutzer ohne `CALENDAR_VIEW` oder deaktivierter Benutzer erhält keine Benachrichtigung bzw. keinen Feed.
- Feed mit unbekanntem oder widerrufenem Token: 404 ohne Hinweis, ob der Token je existierte.
- Titel mit Komma, Semikolon, Zeilenumbruch und Umlauten sowie sehr lange Titel (> 75 Oktette) ergeben gültige, korrekt gefaltete iCal-Zeilen.
- Ganztägiger Termin mit mehreren Tagen: `DTEND` ist der Tag nach dem letzten Tag.
- Zeitzone ohne unterstützte DST-Regeln (Fallback): Ausgabe in UTC statt kaputtem VTIMEZONE.

---

## Dateiübersicht

| Datei | Verantwortung |
|---|---|
| `db/migration/V4__reminders_and_feed.sql` | Tabellen Erinnerungen, Benachrichtigungen, Feed-Token |
| `calendar/ReminderOptions.java` | Erlaubte Offsets und Beschriftungen |
| `notification/Notification.java`, `NotificationRepository.java`, `ReminderService.java`, `ReminderJob.java` | Erzeugung fälliger Benachrichtigungen |
| `web/NotificationController.java`, `web/NavModelAdvice.java`, `templates/notifications.html` | Anzeige, Badge in der Navigation |
| `calendar/ical/VTimeZones.java`, `calendar/ical/ICalWriter.java` | iCal-Ausgabe |
| `calendar/feed/FeedToken.java`, `FeedTokenRepository.java`, `FeedTokenService.java` | Token-Verwaltung |
| `web/FeedController.java`, `web/FeedSettingsController.java`, `templates/calendar/feed.html` | Feed-Endpunkt und Verwaltung |

---

### Task 1: Erinnerungen am Termin

**Files:**
- Create: `db/migration/V4__reminders_and_feed.sql`, `calendar/ReminderOptions.java`
- Modify: `calendar/CalendarEvent.java`, `calendar/EventInput.java`, `calendar/CalendarEventService.java`, `web/EventForm.java`, `templates/calendar/event-form.html`, `web/CalendarController.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/CalendarEventReminderTest.java`

**Interfaces:**
- Produces:
  - `ReminderOptions.OPTIONS: Map<Integer, String>` (Minuten → Beschriftung, Reihenfolge stabil), `ReminderOptions.isAllowed(int)`.
  - `CalendarEvent.getReminderMinutes(): Set<Integer>`, `setReminderMinutes(Set<Integer>)`.
  - `EventInput` bekommt als letzte Komponente `Set<Integer> reminderMinutes`; der bisherige 11-Parameter-Konstruktor bleibt als Kompatibilitätskonstruktor mit `Set.of()` erhalten.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarEventReminderTest {

    @Autowired CalendarEventService service;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    private User user() {
        Role role = roleService.create("Erinnerer", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE"));
        return userService.create("erinnerer", "passwort1", Set.of(role.getId()));
    }

    private EventInput withReminders(Set<Integer> minutes) {
        return new EventInput("Termin", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(), minutes);
    }

    @Test
    void storesReminders() {
        CalendarEvent e = service.create(withReminders(Set.of(10, 60)), user());

        assertThat(e.getReminderMinutes()).containsExactlyInAnyOrder(10, 60);
    }

    @Test
    void rejectsDisallowedOffset() {
        assertThatThrownBy(() -> service.create(withReminders(Set.of(7)), user()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erinnerung");
    }

    @Test
    void compatibilityConstructorMeansNoReminders() {
        EventInput in = new EventInput("Ohne", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of());

        assertThat(in.reminderMinutes()).isEmpty();
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarEventReminderTest`
Expected: Kompilierfehler (12-Parameter-Konstruktor, `getReminderMinutes` fehlen).

- [ ] **Step 3: Implementieren**

`V4__reminders_and_feed.sql`:
```sql
CREATE TABLE calendar_event_reminders (
    event_id       BIGINT  NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    minutes_before INTEGER NOT NULL,
    PRIMARY KEY (event_id, minutes_before)
);

CREATE TABLE notifications (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_id       BIGINT                   NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    recurrence_id  TIMESTAMP WITH TIME ZONE NOT NULL,
    minutes_before INTEGER                  NOT NULL,
    title          VARCHAR(200)             NOT NULL,
    starts_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    read_at        TIMESTAMP WITH TIME ZONE,
    UNIQUE (user_id, event_id, recurrence_id, minutes_before)
);
CREATE INDEX notifications_user_idx ON notifications (user_id, starts_at DESC);

CREATE TABLE calendar_feed_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64)              NOT NULL UNIQUE,
    label      VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
```

`calendar/ReminderOptions.java`:
```java
package club.badernet.nestwerk.calendar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReminderOptions {

    public static final Map<Integer, String> OPTIONS;

    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(0, "Zum Beginn");
        m.put(5, "5 Minuten vorher");
        m.put(10, "10 Minuten vorher");
        m.put(15, "15 Minuten vorher");
        m.put(30, "30 Minuten vorher");
        m.put(60, "1 Stunde vorher");
        m.put(120, "2 Stunden vorher");
        m.put(1440, "1 Tag vorher");
        m.put(2880, "2 Tage vorher");
        m.put(10080, "1 Woche vorher");
        OPTIONS = Collections.unmodifiableMap(m);
    }

    private ReminderOptions() {
    }

    public static boolean isAllowed(int minutes) {
        return OPTIONS.containsKey(minutes);
    }
}
```

`CalendarEvent` ergänzen:
```java
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "calendar_event_reminders", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "minutes_before", nullable = false)
    private Set<Integer> reminderMinutes = new HashSet<>();

    public Set<Integer> getReminderMinutes() { return Set.copyOf(reminderMinutes); }

    public void setReminderMinutes(Set<Integer> minutes) {
        reminderMinutes.clear();
        reminderMinutes.addAll(minutes);
    }
```
(Zwei EAGER-`Set`-Collections plus ein `Set` von Exceptions sind unkritisch; kein `List`/Bag beteiligt, daher keine `MultipleBagFetchException`.)

`EventInput` ersetzen:
```java
package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** Eingabe für Anlegen/Ändern. Bei ganztägigen Terminen ist {@code endDate} inklusiv. */
public record EventInput(String title, String description, String location,
                         LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                         boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds,
                         Set<Integer> reminderMinutes) {

    public EventInput(String title, String description, String location,
                      LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                      boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds) {
        this(title, description, location, startDate, startTime, endDate, endTime,
                allDay, rrule, privateEvent, memberIds, Set.of());
    }
}
```

`CalendarEventService.applyDetails` am Ende ergänzen:
```java
        Set<Integer> reminders = in.reminderMinutes() == null ? Set.of() : new HashSet<>(in.reminderMinutes());
        for (int minutes : reminders) {
            if (!ReminderOptions.isAllowed(minutes)) {
                throw new IllegalArgumentException("Ungültige Erinnerung: " + minutes + " Minuten.");
            }
        }
        event.setReminderMinutes(reminders);
```

`EventForm`: Feld `private Set<Integer> reminderMinutes = new HashSet<>();` mit Getter/Setter (null → leeres Set); `toInput()` übergibt es als letztes Argument; `from(CalendarEvent)` setzt `new HashSet<>(e.getReminderMinutes())`.

`CalendarController.addFormData` ergänzen: `model.addAttribute("reminderOptions", ReminderOptions.OPTIONS);`

`event-form.html` vor dem Speichern-Button:
```html
    <fieldset>
        <legend>Erinnerung (in der App)</legend>
        <label th:each="opt : ${reminderOptions}">
            <input type="checkbox" name="reminderMinutes" th:value="${opt.key}"
                   th:checked="${form.reminderMinutes.contains(opt.key)}">
            <span th:text="${opt.value}"></span>
        </label>
    </fieldset>
```

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=CalendarEventReminderTest+CalendarEventServiceTest+CalendarEditTests`
Expected: PASS (bestehende Phase-2-Tests bleiben grün).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Erinnerungen am Termin erfassen"
```

---

### Task 2: Fällige Erinnerungen erzeugen

**Files:**
- Create: `notification/Notification.java`, `notification/NotificationRepository.java`, `notification/ReminderService.java`, `notification/ReminderJob.java`
- Modify: `calendar/CalendarEventRepository.java`, `NestwerkApplication.java` (`@EnableScheduling`)
- Test: `src/test/java/club/badernet/nestwerk/notification/ReminderServiceTest.java`

**Interfaces:**
- Consumes: `RecurrenceExpander.expand`, `CalendarEvent.getReminderMinutes()`, `FamilyMemberRepository`, `UserRepository`.
- Produces: `ReminderService.generateDue(Instant now): int` (Anzahl neu erzeugter Benachrichtigungen); `NotificationRepository`: `existsByUserIdAndEventIdAndRecurrenceIdAndMinutesBefore(...)`, `findByUserIdOrderByStartsAtDesc(Long, Pageable)`, `countByUserIdAndReadAtIsNull(Long)`, `markAllRead(Long userId, Instant now)`; `Notification` mit `getTitle()`, `getStartsAt()`, `getMinutesBefore()`, `getReadAt()`, `getCreatedAt()`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@Transactional
class ReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T07:30:00Z");

    @Autowired ReminderService reminders;
    @Autowired NotificationRepository notifications;
    @Autowired CalendarEventRepository events;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User owner;
    User partner;

    @BeforeEach
    void setUp() {
        Role viewer = roleService.create("Sieht Kalender", null, Set.of("CALENDAR_VIEW"));
        owner = userService.create("erst", "passwort1", Set.of(viewer.getId()));
        partner = userService.create("partner", "passwort1", Set.of(viewer.getId()));
    }

    private CalendarEvent event(String uid, Instant start, Set<Integer> minutes) {
        CalendarEvent e = new CalendarEvent(uid, "Zahnarzt " + uid, start, start.plusSeconds(3600),
                false, "Europe/Berlin", owner.getId());
        e.setReminderMinutes(minutes);
        return events.saveAndFlush(e);
    }

    private long countFor(User u) {
        return notifications.findByUserIdOrderByStartsAtDesc(u.getId(), PageRequest.of(0, 50)).size();
    }

    @Test
    void createsNotificationWhenReminderIsDue() {
        event("a", NOW.plusSeconds(30 * 60), Set.of(60));

        assertThat(reminders.generateDue(NOW)).isEqualTo(1);
        assertThat(countFor(owner)).isEqualTo(1);
    }

    @Test
    void doesNotCreateDuplicatesOnSecondRun() {
        event("a", NOW.plusSeconds(30 * 60), Set.of(60));

        reminders.generateDue(NOW);

        assertThat(reminders.generateDue(NOW.plusSeconds(60))).isZero();
        assertThat(countFor(owner)).isEqualTo(1);
    }

    @Test
    void notYetDueReminderIsSkipped() {
        event("a", NOW.plusSeconds(3 * 3600), Set.of(60));

        assertThat(reminders.generateDue(NOW)).isZero();
    }

    @Test
    void longPastEventsAreIgnored() {
        event("a", NOW.minusSeconds(3 * 3600), Set.of(0, 60));

        assertThat(reminders.generateDue(NOW)).isZero();
    }

    @Test
    void assignedMemberWithAccountIsNotified() {
        var member = memberService.create("Partner", "#112233", partner.getId());
        CalendarEvent e = event("a", NOW.plusSeconds(30 * 60), Set.of(60));
        e.setMemberIds(Set.of(member.getId()));
        events.saveAndFlush(e);

        reminders.generateDue(NOW);

        assertThat(countFor(owner)).isEqualTo(1);
        assertThat(countFor(partner)).isEqualTo(1);
    }

    @Test
    void privateEventNotifiesOnlyCreator() {
        var member = memberService.create("Partner", "#112233", partner.getId());
        CalendarEvent e = event("a", NOW.plusSeconds(30 * 60), Set.of(60));
        e.setPrivateEvent(true);
        e.setMemberIds(Set.of(member.getId()));
        events.saveAndFlush(e);

        reminders.generateDue(NOW);

        assertThat(countFor(owner)).isEqualTo(1);
        assertThat(countFor(partner)).isZero();
    }

    @Test
    void userWithoutViewPermissionIsNotNotified() {
        Role none = roleService.create("Ohne Kalender", null, Set.of());
        User blind = userService.create("blind", "passwort1", Set.of(none.getId()));
        var member = memberService.create("Blind", "#112233", blind.getId());
        CalendarEvent e = event("a", NOW.plusSeconds(30 * 60), Set.of(60));
        e.setMemberIds(Set.of(member.getId()));
        events.saveAndFlush(e);

        reminders.generateDue(NOW);

        assertThat(countFor(blind)).isZero();
    }

    @Test
    void cancelledOccurrenceOfSeriesIsSkippedButNextOneNotifies() {
        Instant first = NOW.plusSeconds(30 * 60);
        CalendarEvent e = event("serie", first, Set.of(60));
        e.setRrule("FREQ=DAILY");
        e.cancelOccurrence(first);
        events.saveAndFlush(e);

        assertThat(reminders.generateDue(NOW)).isZero();
        assertThat(reminders.generateDue(NOW.plusSeconds(24 * 3600))).isEqualTo(1);
    }

    @Test
    void movedOccurrenceRemindsAtNewTime() {
        Instant original = NOW.plusSeconds(30 * 60);
        CalendarEvent e = event("serie", original, Set.of(60));
        e.setRrule("FREQ=WEEKLY");
        e.overrideOccurrence(original, null, null, null,
                NOW.plusSeconds(5 * 3600), NOW.plusSeconds(6 * 3600));
        events.saveAndFlush(e);

        assertThat(reminders.generateDue(NOW)).isZero();
        assertThat(reminders.generateDue(NOW.plusSeconds(4 * 3600 + 30 * 60))).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=ReminderServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`NestwerkApplication.java`: `@EnableScheduling` an die Klasse (Import `org.springframework.scheduling.annotation.EnableScheduling`).

`CalendarEventRepository` ergänzen:
```java
    @Query("""
            select distinct e from CalendarEvent e
            where e.reminderMinutes is not empty
              and e.startsAt < :to
              and (e.rrule is not null or e.endsAt > :from)
            """)
    List<CalendarEvent> findWithReminders(@Param("from") Instant from, @Param("to") Instant to);
```

`notification/Notification.java`:
```java
package club.badernet.nestwerk.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "recurrence_id", nullable = false)
    private Instant recurrenceId;

    @Column(name = "minutes_before", nullable = false)
    private int minutesBefore;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(Long userId, Long eventId, Instant recurrenceId, int minutesBefore,
                        String title, Instant startsAt) {
        this.userId = userId;
        this.eventId = eventId;
        this.recurrenceId = recurrenceId;
        this.minutesBefore = minutesBefore;
        this.title = title;
        this.startsAt = startsAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public Instant getRecurrenceId() { return recurrenceId; }
    public int getMinutesBefore() { return minutesBefore; }
    public String getTitle() { return title; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
```

`notification/NotificationRepository.java`:
```java
package club.badernet.nestwerk.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByUserIdAndEventIdAndRecurrenceIdAndMinutesBefore(
            Long userId, Long eventId, Instant recurrenceId, int minutesBefore);

    List<Notification> findByUserIdOrderByStartsAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
```

`notification/ReminderService.java`:
```java
package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarPermission;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.RecurrenceExpander;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReminderService {

    /** Größter erlaubter Offset (1 Woche) in Minuten. */
    private static final long LOOKAHEAD_MINUTES = 10_080;
    /** So lange nach Terminbeginn wird noch erinnert (z. B. nach kurzer Betriebspause). */
    private static final Duration GRACE = Duration.ofMinutes(15);

    private final CalendarEventRepository events;
    private final RecurrenceExpander expander;
    private final FamilyMemberRepository members;
    private final UserRepository users;
    private final NotificationRepository notifications;

    public ReminderService(CalendarEventRepository events, RecurrenceExpander expander,
                           FamilyMemberRepository members, UserRepository users,
                           NotificationRepository notifications) {
        this.events = events;
        this.expander = expander;
        this.members = members;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public int generateDue(Instant now) {
        Instant from = now.minus(GRACE);
        Instant horizon = now.plus(Duration.ofMinutes(LOOKAHEAD_MINUTES));
        int created = 0;
        for (CalendarEvent event : events.findWithReminders(from, horizon)) {
            List<User> recipients = recipients(event);
            for (Occurrence occurrence : expander.expand(event, from, horizon)) {
                Instant start = occurrence.start().toInstant();
                if (!start.isAfter(from)) {
                    continue;
                }
                for (int minutes : event.getReminderMinutes()) {
                    Instant due = start.minus(Duration.ofMinutes(minutes));
                    if (due.isAfter(now)) {
                        continue;
                    }
                    for (User user : recipients) {
                        if (notifications.existsByUserIdAndEventIdAndRecurrenceIdAndMinutesBefore(
                                user.getId(), event.getId(), occurrence.recurrenceId(), minutes)) {
                            continue;
                        }
                        notifications.save(new Notification(user.getId(), event.getId(),
                                occurrence.recurrenceId(), minutes, occurrence.title(), start));
                        created++;
                    }
                }
            }
        }
        return created;
    }

    private List<User> recipients(CalendarEvent event) {
        Set<Long> ids = new HashSet<>();
        ids.add(event.getCreatedBy());
        if (!event.isPrivateEvent() && !event.getMemberIds().isEmpty()) {
            for (FamilyMember member : members.findAllById(event.getMemberIds())) {
                if (member.getUserId() != null) {
                    ids.add(member.getUserId());
                }
            }
        }
        return users.findAllById(ids).stream()
                .filter(User::isEnabled)
                .filter(u -> u.permissionNames().contains(CalendarPermission.CALENDAR_VIEW.name()))
                .toList();
    }
}
```

`notification/ReminderJob.java`:
```java
package club.badernet.nestwerk.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "nestwerk.reminders.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    private final ReminderService reminders;

    public ReminderJob(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${nestwerk.reminders.interval-ms:60000}", initialDelay = 15000)
    void run() {
        try {
            int created = reminders.generateDue(Instant.now());
            if (created > 0) {
                log.info("{} Erinnerung(en) erzeugt.", created);
            }
        } catch (RuntimeException e) {
            log.error("Erinnerungen konnten nicht erzeugt werden", e);
        }
    }
}
```
Hinweis: `ConditionalOnProperty` liegt in Boot 4 ggf. in `org.springframework.boot.autoconfigure.condition` (unverändert); bei Compilerfehler den Import anhand der IDE-Suche korrigieren.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=ReminderServiceTest`
Expected: PASS (9 Tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: fällige Erinnerungen als Benachrichtigungen erzeugen"
```

---

### Task 3: Benachrichtigungen anzeigen

**Files:**
- Create: `web/NotificationController.java`, `web/NavModelAdvice.java`, `templates/notifications.html`
- Modify: `templates/fragments/layout.html`
- Test: `src/test/java/club/badernet/nestwerk/web/NotificationControllerTest.java`

**Interfaces:**
- Consumes: `NotificationRepository`, `CurrentUser`.
- Produces: `GET /notifications` (jüngste 50), `POST /notifications/read-all`; Modellattribut `unreadNotifications` (Long) für alle Seiten.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired NotificationRepository notifications;
    @Autowired club.badernet.nestwerk.calendar.CalendarEventRepository eventRepository;

    User mine;
    User theirs;

    @BeforeEach
    void setUp() {
        Role viewer = roleService.create("Notif", null, Set.of("CALENDAR_VIEW"));
        mine = userService.create("notif-ich", "passwort1", Set.of(viewer.getId()));
        theirs = userService.create("notif-du", "passwort1", Set.of(viewer.getId()));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor me() {
        return user("notif-ich").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    @Test
    void listsOnlyOwnNotifications() throws Exception {
        // notifications.event_id ist ein Fremdschlüssel, daher zuerst einen echten Termin anlegen
        var event = new club.badernet.nestwerk.calendar.CalendarEvent("n-1", "E", Instant.now(),
                Instant.now().plusSeconds(60), false, "Europe/Berlin", mine.getId());
        var saved = eventRepository.saveAndFlush(event);
        notifications.saveAndFlush(new Notification(mine.getId(), saved.getId(), Instant.now(), 60,
                "Mein Arzttermin", Instant.now().plusSeconds(3600)));
        notifications.saveAndFlush(new Notification(theirs.getId(), saved.getId(), Instant.now(), 60,
                "Dein Zahnarzt", Instant.now().plusSeconds(3600)));

        mockMvc.perform(get("/notifications").with(me()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mein Arzttermin")))
                .andExpect(content().string(not(containsString("Dein Zahnarzt"))));
    }

    @Test
    void readAllMarksOwnNotificationsRead() throws Exception {
        var event = new club.badernet.nestwerk.calendar.CalendarEvent("n-2", "E", Instant.now(),
                Instant.now().plusSeconds(60), false, "Europe/Berlin", mine.getId());
        var saved = eventRepository.saveAndFlush(event);
        notifications.saveAndFlush(new Notification(mine.getId(), saved.getId(), Instant.now(), 60,
                "Ungelesen", Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/notifications/read-all").with(csrf()).with(me()))
                .andExpect(status().is3xxRedirection());

        assertThat(notifications.countByUserIdAndReadAtIsNull(mine.getId())).isZero();
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=NotificationControllerTest`
Expected: FAIL (404, Controller fehlt).

- [ ] **Step 3: Implementieren**

`web/NotificationController.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.notification.NotificationRepository;
import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;

@Controller
@RequestMapping("/notifications")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class NotificationController {

    private final NotificationRepository notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationRepository notifications, CurrentUser currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    String list(Model model) {
        Long userId = currentUser.require().getId();
        model.addAttribute("notifications",
                notifications.findByUserIdOrderByStartsAtDesc(userId, PageRequest.of(0, 50)));
        return "notifications";
    }

    @PostMapping("/read-all")
    @Transactional
    String readAll() {
        notifications.markAllRead(currentUser.require().getId(), Instant.now());
        return "redirect:/notifications";
    }
}
```

`web/NavModelAdvice.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.notification.NotificationRepository;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
class NavModelAdvice {

    private final UserRepository users;
    private final NotificationRepository notifications;

    NavModelAdvice(UserRepository users, NotificationRepository notifications) {
        this.users = users;
        this.notifications = notifications;
    }

    @ModelAttribute("unreadNotifications")
    Long unreadNotifications() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return 0L;
        }
        return users.findByUsername(auth.getName())
                .map(u -> notifications.countByUserIdAndReadAtIsNull(u.getId()))
                .orElse(0L);
    }
}
```

`templates/notifications.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Benachrichtigungen')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Benachrichtigungen</h1>
<form th:action="@{/notifications/read-all}" method="post">
    <button type="submit">Alle als gelesen markieren</button>
</form>
<p th:if="${notifications.isEmpty()}" class="muted">Keine Benachrichtigungen.</p>
<ul>
    <li th:each="n : ${notifications}" th:classappend="${n.readAt == null} ? 'unread'">
        <strong th:text="${n.title}"></strong>
        – <span th:text="${#temporals.format(n.startsAt, 'dd.MM.yyyy HH:mm')}"></span>
        <span th:text="${n.minutesBefore == 0} ? '(Beginn)' : '(Erinnerung ' + ${n.minutesBefore} + ' Min. vorher)'"></span>
    </li>
</ul>
</main>
</body>
</html>
```
(`#temporals.format` mit `Instant` benötigt eine Zeitzone; falls Thymeleaf `Instant` nicht formatiert, in `Notification` eine Methode `startsAtLocal(ZoneId)` ergänzen oder im Controller auf `ZonedDateTime` in `CalendarSettings.zone()` mappen.)

`layout.html` im `nav` nach „Kalender“:
```html
    <a th:href="@{/notifications}" sec:authorize="hasAuthority('CALENDAR_VIEW')">Benachrichtigungen<span
            th:if="${unreadNotifications != null and unreadNotifications > 0}"
            th:text="' (' + ${unreadNotifications} + ')'"></span></a>
```
`nestwerk.css`: `li.unread { font-weight: bold; }`

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=NotificationControllerTest+NavigationTests`
Expected: PASS. (`NavigationTests` mit `@WithMockUser` ohne DB-User: `NavModelAdvice` liefert 0.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Benachrichtigungsliste und Badge in der Navigation"
```

---

### Task 4: iCal-Ausgabe (VTIMEZONE und ICalWriter)

**Files:**
- Create: `calendar/ical/VTimeZones.java`, `calendar/ical/ICalWriter.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/ical/VTimeZonesTest.java`, `src/test/java/club/badernet/nestwerk/calendar/ical/ICalWriterTest.java`

**Interfaces:**
- Produces:
  - `VTimeZones.of(ZoneId): Optional<List<String>>` – VTIMEZONE-Zeilen (ohne Zeilenenden) oder leer, wenn die Regeln nicht darstellbar sind.
  - `ICalWriter.write(String calendarName, Collection<CalendarEvent> events): String` (CRLF, gefaltet); `ICalWriter.fold(String line): String` (statisch, öffentlich, für Tests).

- [ ] **Step 1: Failing Tests schreiben**

`VTimeZonesTest.java`:
```java
package club.badernet.nestwerk.calendar.ical;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VTimeZonesTest {

    @Test
    void berlinHasDaylightAndStandardWithLastSundayRules() {
        List<String> lines = VTimeZones.of(ZoneId.of("Europe/Berlin")).orElseThrow();

        assertThat(lines).contains("BEGIN:VTIMEZONE", "TZID:Europe/Berlin",
                "BEGIN:DAYLIGHT", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200",
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU", "DTSTART:19700329T020000",
                "BEGIN:STANDARD", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100",
                "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU", "DTSTART:19701025T030000",
                "END:VTIMEZONE");
    }

    @Test
    void newYorkUsesSundayOnOrAfterRules() {
        List<String> lines = VTimeZones.of(ZoneId.of("America/New_York")).orElseThrow();

        assertThat(lines).contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=8,9,10,11,12,13,14");
    }

    @Test
    void fixedOffsetZoneHasOnlyStandard() {
        List<String> lines = VTimeZones.of(ZoneId.of("Asia/Tokyo")).orElseThrow();

        assertThat(lines).contains("BEGIN:STANDARD", "TZOFFSETTO:+0900").doesNotContain("BEGIN:DAYLIGHT");
    }

    @Test
    void utcIsNotNeeded() {
        assertThat(VTimeZones.of(ZoneId.of("UTC"))).isEmpty();
    }
}
```

`ICalWriterTest.java`:
```java
package club.badernet.nestwerk.calendar.ical;

import club.badernet.nestwerk.calendar.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ICalWriterTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final ICalWriter writer = new ICalWriter();

    private static Instant at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, BERLIN).toInstant();
    }

    private static CalendarEvent timed(String title) {
        return new CalendarEvent("uid-1@nestwerk", title, at(2026, 10, 12, 10, 0), at(2026, 10, 12, 11, 0),
                false, "Europe/Berlin", 1L);
    }

    private String render(CalendarEvent... events) {
        return writer.write("Familie", List.of(events));
    }

    @Test
    void usesCrLfAndWrapsInVCalendar() {
        String ics = render(timed("Arzt"));

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(ics).contains("X-WR-CALNAME:Familie");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void writesTimedEventWithTzid() {
        String ics = render(timed("Arzt"));

        assertThat(ics).contains("UID:uid-1@nestwerk", "SUMMARY:Arzt",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "DTEND;TZID=Europe/Berlin:20261012T110000",
                "BEGIN:VTIMEZONE", "TZID:Europe/Berlin");
    }

    @Test
    void writesAllDayEventAsDateWithExclusiveEnd() {
        CalendarEvent e = new CalendarEvent("uid-2@nestwerk", "Urlaub", at(2026, 10, 12, 0, 0),
                at(2026, 10, 15, 0, 0), true, "Europe/Berlin", 1L);

        String ics = render(e);

        assertThat(ics).contains("DTSTART;VALUE=DATE:20261012", "DTEND;VALUE=DATE:20261015");
    }

    @Test
    void escapesSpecialCharactersInText() {
        CalendarEvent e = timed("Kino, Pizza; Eis\\Rest");
        e.setDescription("Zeile 1\nZeile 2");
        e.setLocation("Küche, 2. OG");

        String ics = render(e);

        assertThat(ics).contains("SUMMARY:Kino\\, Pizza\\; Eis\\\\Rest",
                "DESCRIPTION:Zeile 1\\nZeile 2", "LOCATION:Küche\\, 2. OG");
    }

    @Test
    void foldsLongLinesAtSeventyFiveOctetsWithoutSplittingCharacters() {
        String folded = ICalWriter.fold("SUMMARY:" + "ä".repeat(60));

        for (String line : folded.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(folded.replace("\r\n ", "")).isEqualTo("SUMMARY:" + "ä".repeat(60));
    }

    @Test
    void writesRecurrenceWithExdateAndOverride() {
        CalendarEvent e = timed("Training");
        e.setRrule("FREQ=WEEKLY");
        e.cancelOccurrence(at(2026, 10, 19, 10, 0));
        e.overrideOccurrence(at(2026, 10, 26, 10, 0), "Training (Halle)", null, null,
                at(2026, 10, 26, 18, 0), at(2026, 10, 26, 19, 0));

        String ics = render(e);

        assertThat(ics).contains("RRULE:FREQ=WEEKLY",
                "EXDATE;TZID=Europe/Berlin:20261019T100000",
                "RECURRENCE-ID;TZID=Europe/Berlin:20261026T100000",
                "SUMMARY:Training (Halle)",
                "DTSTART;TZID=Europe/Berlin:20261026T180000");
        assertThat(count(ics, "BEGIN:VEVENT")).isEqualTo(2);
    }

    @Test
    void marksPrivateEventsAsClassPrivate() {
        CalendarEvent e = timed("Geheim");
        e.setPrivateEvent(true);

        assertThat(render(e)).contains("CLASS:PRIVATE");
    }

    @Test
    void writesReminders() {
        CalendarEvent e = timed("Mit Alarm");
        e.setReminderMinutes(Set.of(0, 60));

        String ics = render(e);

        assertThat(ics).contains("BEGIN:VALARM", "TRIGGER:-PT60M", "TRIGGER:PT0S", "ACTION:DISPLAY");
    }

    @Test
    void fallsBackToUtcForZonesWithoutVTimezone() {
        CalendarEvent e = new CalendarEvent("uid-3@nestwerk", "UTC-Termin", at(2026, 10, 12, 10, 0),
                at(2026, 10, 12, 11, 0), false, "UTC", 1L);

        String ics = render(e);

        assertThat(ics).contains("DTSTART:20261012T080000Z").doesNotContain("BEGIN:VTIMEZONE");
    }

    @Test
    void includesSequenceAndStableUid() {
        CalendarEvent e = timed("Version");
        e.markChanged();
        e.markChanged();

        assertThat(render(e)).contains("SEQUENCE:2", "UID:uid-1@nestwerk");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
```

- [ ] **Step 2: Tests ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=VTimeZonesTest+ICalWriterTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`calendar/ical/VTimeZones.java`:
```java
package club.badernet.nestwerk.calendar.ical;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Erzeugt VTIMEZONE-Blöcke aus den java.time-Regeln für Zonen mit höchstens einem DST-Paar. */
final class VTimeZones {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private VTimeZones() {
    }

    static Optional<List<String>> of(ZoneId zone) {
        if (zone.equals(ZoneOffset.UTC) || zone.getId().equals("UTC") || zone.getId().equals("Z")) {
            return Optional.empty();
        }
        ZoneRules rules = zone.getRules();
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VTIMEZONE");
        lines.add("TZID:" + zone.getId());
        if (rules.getTransitionRules().isEmpty()) {
            ZoneOffset offset = rules.getOffset(java.time.Instant.EPOCH);
            if (!rules.isFixedOffset()) {
                return Optional.empty();
            }
            lines.add("BEGIN:STANDARD");
            lines.add("DTSTART:19700101T000000");
            lines.add("TZOFFSETFROM:" + offset(offset));
            lines.add("TZOFFSETTO:" + offset(offset));
            lines.add("END:STANDARD");
        } else if (rules.getTransitionRules().size() == 2) {
            for (ZoneOffsetTransitionRule rule : rules.getTransitionRules()) {
                Optional<List<String>> block = block(rule);
                if (block.isEmpty()) {
                    return Optional.empty();
                }
                lines.addAll(block.get());
            }
        } else {
            return Optional.empty();
        }
        lines.add("END:VTIMEZONE");
        return Optional.of(lines);
    }

    private static Optional<List<String>> block(ZoneOffsetTransitionRule rule) {
        boolean daylight = rule.getOffsetAfter().getTotalSeconds() > rule.getOffsetBefore().getTotalSeconds();
        DayOfWeek dow = rule.getDayOfWeek();
        int indicator = rule.getDayOfMonthIndicator();
        if (dow == null || indicator == 0 || indicator < -1) {
            return Optional.empty();
        }
        String byDay = dow.name().substring(0, 2);
        String recurrence = "FREQ=YEARLY;BYMONTH=" + rule.getMonth().getValue();
        if (indicator == -1) {
            recurrence += ";BYDAY=-1" + byDay;
        } else {
            String days = IntStream.range(indicator, indicator + 7).boxed()
                    .map(String::valueOf).collect(Collectors.joining(","));
            recurrence += ";BYDAY=" + byDay + ";BYMONTHDAY=" + days;
        }
        ZoneOffsetTransition first = rule.createTransition(1970);
        LocalDateTime start = first.getDateTimeBefore();
        String name = daylight ? "DAYLIGHT" : "STANDARD";
        return Optional.of(List.of(
                "BEGIN:" + name,
                "DTSTART:" + LOCAL.format(start),
                "TZOFFSETFROM:" + offset(rule.getOffsetBefore()),
                "TZOFFSETTO:" + offset(rule.getOffsetAfter()),
                "RRULE:" + recurrence,
                "END:" + name));
    }

    private static String offset(ZoneOffset offset) {
        int total = offset.getTotalSeconds();
        char sign = total < 0 ? '-' : '+';
        int abs = Math.abs(total);
        return String.format("%c%02d%02d", sign, abs / 3600, (abs % 3600) / 60);
    }
}
```
(Die Klasse und `of` sind paketprivat; `VTimeZonesTest` liegt im selben Paket.)

`calendar/ical/ICalWriter.java`:
```java
package club.badernet.nestwerk.calendar.ical;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ICalWriter {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public String write(String calendarName, Collection<CalendarEvent> events) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:-//Nestwerk//Familienkalender//DE");
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:PUBLISH");
        lines.add("X-WR-CALNAME:" + escape(calendarName));

        Map<String, Boolean> zoneSupported = new LinkedHashMap<>();
        for (CalendarEvent e : events) {
            zoneSupported.computeIfAbsent(e.getTimezone(), tz -> {
                Optional<List<String>> block = VTimeZones.of(ZoneId.of(tz));
                block.ifPresent(lines::addAll);
                return block.isPresent();
            });
        }
        for (CalendarEvent e : events) {
            writeEvent(lines, e, zoneSupported.get(e.getTimezone()));
        }
        lines.add("END:VCALENDAR");

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(fold(line)).append("\r\n");
        }
        return out.toString();
    }

    private void writeEvent(List<String> lines, CalendarEvent e, boolean tzSupported) {
        ZoneId zone = ZoneId.of(e.getTimezone());
        Duration length = Duration.between(e.getStartsAt(), e.getEndsAt());
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + e.getUid());
        lines.add("DTSTAMP:" + UTC.format(e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.now()));
        lines.add("SEQUENCE:" + e.getSequence());
        lines.add("SUMMARY:" + escape(e.getTitle()));
        optional(lines, "DESCRIPTION", e.getDescription());
        optional(lines, "LOCATION", e.getLocation());
        if (e.isPrivateEvent()) {
            lines.add("CLASS:PRIVATE");
        }
        lines.add(dt("DTSTART", e.getStartsAt(), e.isAllDay(), zone, tzSupported));
        lines.add(dt("DTEND", e.getEndsAt(), e.isAllDay(), zone, tzSupported));
        if (e.getRrule() != null) {
            lines.add("RRULE:" + e.getRrule());
        }
        List<CalendarEventException> exceptions = e.getExceptions().stream()
                .sorted(Comparator.comparing(CalendarEventException::getRecurrenceId)).toList();
        for (CalendarEventException ex : exceptions) {
            if (ex.isCancelled()) {
                lines.add(dt("EXDATE", ex.getRecurrenceId(), e.isAllDay(), zone, tzSupported));
            }
        }
        for (int minutes : e.getReminderMinutes().stream().sorted().toList()) {
            lines.add("BEGIN:VALARM");
            lines.add("ACTION:DISPLAY");
            lines.add("DESCRIPTION:" + escape(e.getTitle()));
            lines.add(minutes == 0 ? "TRIGGER:PT0S" : "TRIGGER:-PT" + minutes + "M");
            lines.add("END:VALARM");
        }
        lines.add("END:VEVENT");

        for (CalendarEventException ex : exceptions) {
            if (ex.isCancelled()) {
                continue;
            }
            Instant start = ex.getStartsAt() != null ? ex.getStartsAt() : ex.getRecurrenceId();
            Instant end = ex.getEndsAt() != null ? ex.getEndsAt() : start.plus(length);
            lines.add("BEGIN:VEVENT");
            lines.add("UID:" + e.getUid());
            lines.add("DTSTAMP:" + UTC.format(e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.now()));
            lines.add("SEQUENCE:" + e.getSequence());
            lines.add(dt("RECURRENCE-ID", ex.getRecurrenceId(), e.isAllDay(), zone, tzSupported));
            lines.add("SUMMARY:" + escape(ex.getTitle() != null ? ex.getTitle() : e.getTitle()));
            optional(lines, "DESCRIPTION", ex.getDescription() != null ? ex.getDescription() : e.getDescription());
            optional(lines, "LOCATION", ex.getLocation() != null ? ex.getLocation() : e.getLocation());
            if (e.isPrivateEvent()) {
                lines.add("CLASS:PRIVATE");
            }
            lines.add(dt("DTSTART", start, e.isAllDay(), zone, tzSupported));
            lines.add(dt("DTEND", end, e.isAllDay(), zone, tzSupported));
            lines.add("END:VEVENT");
        }
    }

    private String dt(String name, Instant instant, boolean allDay, ZoneId zone, boolean tzSupported) {
        if (allDay) {
            return name + ";VALUE=DATE:" + DATE.format(instant.atZone(zone));
        }
        if (tzSupported) {
            return name + ";TZID=" + zone.getId() + ":" + LOCAL.format(instant.atZone(zone));
        }
        return name + ":" + UTC.format(instant);
    }

    private void optional(List<String> lines, String name, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(name + ":" + escape(value));
        }
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /** Faltet eine Zeile bei 75 Oktetten (UTF-8); Folgezeilen beginnen mit einem Leerzeichen. */
    public static String fold(String line) {
        StringBuilder out = new StringBuilder();
        int limit = 75;
        int used = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int bytes = ch.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > limit) {
                out.append("\r\n ");
                used = 1;
                limit = 75;
            }
            out.append(ch);
            used += bytes;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
```
Hinweis: Das Test-Paket `calendar.ical` greift auf `VTimeZones` (paketprivat) zu – passt. `ICalWriter.escape` ist paketprivat/statisch für spätere Wiederverwendung im selben Paket.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=VTimeZonesTest+ICalWriterTest`
Expected: PASS. Falls `fixedOffsetZoneHasOnlyStandard` für `Asia/Tokyo` fehlschlägt (Tokyo hat historische Sommerzeit, `getTransitionRules()` ist leer, `isFixedOffset()` aber `false`): in `VTimeZones.of` die Bedingung auf „`getTransitionRules().isEmpty()` → aktuellen Offset (`rules.getOffset(Instant.now())`) als fixen STANDARD-Block verwenden“ ändern und `isFixedOffset()` entfernen.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: iCal-Ausgabe mit VTIMEZONE, Faltung und Escaping"
```

---

### Task 5: Feed-Token und Feed-Endpunkt

**Files:**
- Create: `calendar/feed/FeedToken.java`, `calendar/feed/FeedTokenRepository.java`, `calendar/feed/FeedTokenService.java`
- Create: `web/FeedController.java`, `web/FeedSettingsController.java`, `templates/calendar/feed.html`
- Modify: `config/SecurityConfig.java` (`/feed/**` permitAll), `calendar/CalendarEventRepository.java`, `calendar/CalendarEventService.java` (`findAllVisible`), `templates/fragments/layout.html`, `templates/calendar/fragments.html` (Link „Abo“)
- Test: `src/test/java/club/badernet/nestwerk/calendar/feed/FeedTokenServiceTest.java`, `src/test/java/club/badernet/nestwerk/web/FeedControllerTest.java`

**Interfaces:**
- Produces:
  - `FeedTokenService.create(User, String label): String` (Klartext-Token, einmalig), `list(User): List<FeedToken>`, `revoke(Long tokenId, User): void` (nur eigene), `resolve(String rawToken): Optional<User>`.
  - `CalendarEventService.findAllVisible(User viewer): List<CalendarEvent>`.
  - `GET /feed/{token}.ics` (öffentlich, `text/calendar`), `GET /calendar/feed`, `POST /calendar/feed`, `POST /calendar/feed/{id}/revoke`.

- [ ] **Step 1: Failing Tests schreiben**

`FeedTokenServiceTest.java`:
```java
package club.badernet.nestwerk.calendar.feed;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@Transactional
class FeedTokenServiceTest {

    @Autowired FeedTokenService tokens;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void resolvesTokenToItsUser() {
        User user = userService.create("feed-ich", "passwort1", Set.of());

        String raw = tokens.create(user, "Handy");

        assertThat(tokens.resolve(raw)).get().extracting(User::getId).isEqualTo(user.getId());
    }

    @Test
    void storesOnlyHashNeverTheRawToken() {
        User user = userService.create("feed-hash", "passwort1", Set.of());

        String raw = tokens.create(user, null);

        Integer plain = jdbc.queryForObject(
                "SELECT count(*) FROM calendar_feed_tokens WHERE token_hash = ?", Integer.class, raw);
        assertThat(plain).isZero();
        assertThat(raw.length()).isGreaterThanOrEqualTo(40);
    }

    @Test
    void unknownTokenResolvesToNothing() {
        assertThat(tokens.resolve("gibt-es-nicht")).isEmpty();
    }

    @Test
    void revokedTokenStopsWorking() {
        User user = userService.create("feed-weg", "passwort1", Set.of());
        String raw = tokens.create(user, "alt");
        Long id = tokens.list(user).getFirst().getId();

        tokens.revoke(id, user);

        assertThat(tokens.resolve(raw)).isEmpty();
    }

    @Test
    void foreignTokenCannotBeRevoked() {
        User owner = userService.create("feed-a", "passwort1", Set.of());
        User other = userService.create("feed-b", "passwort1", Set.of());
        tokens.create(owner, "meins");
        Long id = tokens.list(owner).getFirst().getId();

        assertThatThrownBy(() -> tokens.revoke(id, other)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledUserTokenDoesNotResolve() {
        User user = userService.create("feed-aus", "passwort1", Set.of());
        String raw = tokens.create(user, null);

        userService.setEnabled(user.getId(), false);

        assertThat(tokens.resolve(raw)).isEmpty();
    }
}
```

`FeedControllerTest.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.feed.FeedTokenService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class FeedControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired CalendarEventService events;
    @Autowired FeedTokenService tokens;

    User owner;
    User other;

    @BeforeEach
    void setUp() {
        Role role = roleService.create("Feed", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_PRIVATE_CREATE"));
        owner = userService.create("feed-owner", "passwort1", Set.of(role.getId()));
        other = userService.create("feed-other", "passwort1", Set.of(role.getId()));
        events.create(new EventInput("Offener Termin", null, null, LocalDate.of(2026, 10, 12),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false,
                Set.of()), owner);
        events.create(new EventInput("Privater Termin", null, null, LocalDate.of(2026, 10, 13),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 13), LocalTime.of(11, 0), false, null, true,
                Set.of()), owner);
    }

    @Test
    void feedIsPublicAndContainsVisibleEventsOnly() throws Exception {
        String token = tokens.create(other, "Handy");

        mockMvc.perform(get("/feed/" + token + ".ics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/calendar")))
                .andExpect(content().string(containsString("BEGIN:VCALENDAR")))
                .andExpect(content().string(containsString("SUMMARY:Offener Termin")))
                .andExpect(content().string(not(containsString("Privater Termin"))));
    }

    @Test
    void ownersFeedContainsOwnPrivateEvents() throws Exception {
        String token = tokens.create(owner, "Handy");

        mockMvc.perform(get("/feed/" + token + ".ics"))
                .andExpect(content().string(containsString("SUMMARY:Privater Termin")));
    }

    @Test
    void unknownTokenIsNotFound() throws Exception {
        mockMvc.perform(get("/feed/falscher-token.ics")).andExpect(status().isNotFound());
    }

    @Test
    void revokedTokenIsNotFound() throws Exception {
        String token = tokens.create(owner, "alt");
        tokens.revoke(tokens.list(owner).getFirst().getId(), owner);

        mockMvc.perform(get("/feed/" + token + ".ics")).andExpect(status().isNotFound());
    }

    @Test
    void userWithoutViewPermissionGetsNotFound() throws Exception {
        Role none = roleService.create("Ohne Sicht", null, Set.of());
        User blind = userService.create("feed-blind", "passwort1", Set.of(none.getId()));
        String token = tokens.create(blind, null);

        mockMvc.perform(get("/feed/" + token + ".ics")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Tests ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=FeedTokenServiceTest+FeedControllerTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`calendar/feed/FeedToken.java`:
```java
package club.badernet.nestwerk.calendar.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "calendar_feed_tokens")
public class FeedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(length = 100)
    private String label;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected FeedToken() {
    }

    public FeedToken(Long userId, String tokenHash, String label) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.label = label;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`calendar/feed/FeedTokenRepository.java`:
```java
package club.badernet.nestwerk.calendar.feed;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedTokenRepository extends JpaRepository<FeedToken, Long> {

    Optional<FeedToken> findByTokenHash(String tokenHash);

    List<FeedToken> findByUserIdOrderByCreatedAtDesc(Long userId);
}
```

`calendar/feed/FeedTokenService.java`:
```java
package club.badernet.nestwerk.calendar.feed;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class FeedTokenService {

    private final SecureRandom random = new SecureRandom();
    private final FeedTokenRepository tokens;
    private final UserRepository users;

    public FeedTokenService(FeedTokenRepository tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Transactional
    public String create(User user, String label) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String cleanLabel = label == null || label.isBlank() ? null : label.trim();
        if (cleanLabel != null && cleanLabel.length() > 100) {
            cleanLabel = cleanLabel.substring(0, 100);
        }
        tokens.save(new FeedToken(user.getId(), hash(raw), cleanLabel));
        return raw;
    }

    @Transactional(readOnly = true)
    public List<FeedToken> list(User user) {
        return tokens.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public void revoke(Long tokenId, User user) {
        FeedToken token = tokens.findById(tokenId)
                .filter(t -> t.getUserId().equals(user.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Abo-Link nicht gefunden"));
        tokens.delete(token);
    }

    @Transactional(readOnly = true)
    public Optional<User> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(hash(rawToken))
                .flatMap(t -> users.findById(t.getUserId()))
                .filter(User::isEnabled);
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`CalendarEventRepository` ergänzen:
```java
    @Query("""
            select distinct e from CalendarEvent e
            where e.privateEvent = false or e.createdBy = :viewerId or :admin = true
            order by e.id
            """)
    List<CalendarEvent> findAllVisible(@Param("viewerId") Long viewerId, @Param("admin") boolean admin);
```
`CalendarEventService` ergänzen:
```java
    @Transactional(readOnly = true)
    public List<CalendarEvent> findAllVisible(User viewer) {
        require(viewer, CalendarPermission.CALENDAR_VIEW);
        return events.findAllVisible(viewer.getId(), viewer.isAdministrator());
    }
```
(`distinct` zusammen mit `order by e.id` ist in HQL zulässig, da `e.id` Teil der Selektion ist.)

`SecurityConfig`: in `permitAll()` die Matcher um `"/feed/**"` erweitern.

`web/FeedController.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.calendar.feed.FeedTokenService;
import club.badernet.nestwerk.calendar.ical.ICalWriter;
import club.badernet.nestwerk.user.User;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Controller
public class FeedController {

    private final FeedTokenService tokens;
    private final CalendarEventService events;
    private final ICalWriter writer;

    public FeedController(FeedTokenService tokens, CalendarEventService events, ICalWriter writer) {
        this.tokens = tokens;
        this.events = events;
        this.writer = writer;
    }

    @GetMapping("/feed/{token}.ics")
    ResponseEntity<String> feed(@PathVariable String token) {
        User user = tokens.resolve(token).orElseThrow(EventNotFoundException::new);
        List<CalendarEvent> visible;
        try {
            visible = events.findAllVisible(user);
        } catch (AccessDeniedException e) {
            throw new EventNotFoundException();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .body(writer.write("Nestwerk Familienkalender", visible));
    }
}
```
Hinweis: `EventNotFoundException` (404 via `@ResponseStatus`) wird bewusst für alle Fehlerfälle wiederverwendet, damit nicht erkennbar ist, ob ein Token existierte.

`web/FeedSettingsController.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.feed.FeedTokenService;
import club.badernet.nestwerk.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/calendar/feed")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class FeedSettingsController {

    private final FeedTokenService tokens;
    private final CurrentUser currentUser;

    public FeedSettingsController(FeedTokenService tokens, CurrentUser currentUser) {
        this.tokens = tokens;
        this.currentUser = currentUser;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("tokens", tokens.list(currentUser.require()));
        return "calendar/feed";
    }

    @PostMapping
    String create(@RequestParam(required = false) String label, HttpServletRequest request,
                  RedirectAttributes redirect) {
        String raw = tokens.create(currentUser.require(), label);
        String url = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/feed/{token}.ics").buildAndExpand(raw).toUriString();
        redirect.addFlashAttribute("newFeedUrl", url);
        return "redirect:/calendar/feed";
    }

    @PostMapping("/{id}/revoke")
    String revoke(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            tokens.revoke(id, currentUser.require());
            redirect.addFlashAttribute("success", "Abo-Link wurde widerrufen.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/feed";
    }
}
```

`templates/calendar/feed.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Kalender abonnieren')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Kalender abonnieren</h1>
<p>Mit einem Abo-Link kannst du den Familienkalender (nur lesend) in Android, Outlook oder andere
    Kalender-Apps einbinden. Für Bearbeiten auf dem Handy siehe CalDAV-Sync.</p>
<p th:if="${success}" class="flash-success" th:text="${success}"></p>
<p th:if="${error}" class="flash-error" th:text="${error}"></p>

<section th:if="${newFeedUrl}">
    <h2>Neuer Abo-Link</h2>
    <p><strong>Wird nur jetzt angezeigt.</strong> Wer den Link kennt, sieht deine sichtbaren Termine.</p>
    <p><input type="text" readonly size="80" th:value="${newFeedUrl}" onclick="this.select()"></p>
</section>

<h2>Vorhandene Links</h2>
<p th:if="${tokens.isEmpty()}" class="muted">Noch keine Abo-Links.</p>
<ul>
    <li th:each="t : ${tokens}">
        <span th:text="${t.label != null ? t.label : 'Ohne Bezeichnung'}"></span>
        – erstellt <span th:text="${#temporals.format(t.createdAt, 'dd.MM.yyyy HH:mm')}"></span>
        <form th:action="@{/calendar/feed/{id}/revoke(id=${t.id})}" method="post" style="display:inline">
            <button type="submit">Widerrufen</button>
        </form>
    </li>
</ul>

<h2>Neuen Link erzeugen</h2>
<form th:action="@{/calendar/feed}" method="post">
    <label>Bezeichnung <input type="text" name="label" maxlength="100" placeholder="z. B. Mein Handy"></label>
    <button type="submit">Erzeugen</button>
</form>
</main>
</body>
</html>
```

`templates/calendar/fragments.html` im `viewswitch` ergänzen: `<a th:href="@{/calendar/feed}">Abo</a>`.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=FeedTokenServiceTest+FeedControllerTest`
Expected: PASS (11 Tests).

- [ ] **Step 5: Gesamtlauf und manuelle Prüfung**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

Manuell: Termin mit Erinnerung „5 Minuten vorher“ in 6 Minuten anlegen → nach ≤ 2 Minuten erscheint die Benachrichtigung samt Badge in der Navigation. Unter „Kalender → Abo“ Link erzeugen, in Android (z. B. ICSx5) oder per `curl <url>` abrufen: gültige `.ics`; Link widerrufen → 404.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: abonnierbarer iCal-Feed mit widerrufbaren Tokens"
```
