# Phase 2: Kalender-Kern – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Familienkalender mit Terminen (einzeln, ganztägig, volle iCal-RRULE), Ausnahmen einzelner Vorkommen, Zuweisung an Mitglieder, privaten Terminen sowie Monats-, Wochen- und Agenda-Ansicht.

**Architecture:** Termine werden als Stammdatensatz (`calendar_events`) mit optionaler RRULE gespeichert; Ausnahmen/Änderungen einzelner Vorkommen in `calendar_event_exceptions`. Die Expansion der Wiederholungen übernimmt ical4j (`Recur`), gekapselt im `RecurrenceExpander`. Sichtbarkeit privater Termine wird bereits in der DB-Abfrage gefiltert. Rechte werden im `CalendarEventService` geprüft, nicht nur in der UI.

**Tech Stack:** wie Phase 1, zusätzlich `org.mnode.ical4j:ical4j:4.4.0` (verifiziert: `Recur<T extends Temporal>`, `new Recur<>(String)`, `getDates(T seed, Temporal periodStart, Temporal periodEnd): List<T>`).

**Spec:** `docs/superpowers/specs/2026-10-13-basis-rollen-mitglieder-design.md`
**Voraussetzung:** Phase 1 ist umgesetzt (`…phase-1-rollen-mitglieder-layout.md`).

## Global Constraints

- Wiederholungen werden mit ical4j expandiert, nicht selbst implementiert.
- Private Termine werden serverseitig gefiltert (DB-Abfrage + Einzelzugriff), nie nur in der Ansicht ausgeblendet. Sichtbar für Ersteller und Administrator.
- Standard-Zeitzone `Europe/Berlin`, konfigurierbar über `nestwerk.timezone`; Zeitpunkte werden als `Instant` gespeichert.
- Ganztägige Termine: `starts_at` = Tagesbeginn des ersten Tags, `ends_at` = Tagesbeginn des Tages **nach** dem letzten Tag (iCal-Konvention, Ende exklusiv). Im Formular wird das Enddatum inklusiv eingegeben.
- Flyway: neue Version `V3__calendar.sql`; V1/V2 bleiben unverändert.
- UI-Texte Deutsch mit Umlauten; Rechte-Namen sind Enum-Namen (`CALENDAR_*`).
- Commit-Nachrichten enden mit `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Testkonventionen wie in Phase 1 (Testcontainers, `@Transactional`).

## Review Focus

- Ende nicht nach Beginn (gleich oder davor) wird mit verständlicher Meldung abgelehnt; ganztägig mit Enddatum vor Startdatum ebenso.
- Termin über Mitternacht (31.10. 23:30 → 1.11. 00:30) erscheint in beiden Tageszellen; ganztägiger Eintagestermin nur in einer.
- Serie mit `UNTIL` in der Vergangenheit liefert im aktuellen Monat nichts, ohne Fehler.
- Privater Termin eines anderen Benutzers taucht weder in Monat, Woche, Agenda noch über direkte URL `/calendar/events/{id}` auf (404, nicht 403, kein Titel-Leak).
- Ungültiger `date`-Parameter (`?date=abc`) fällt auf heute zurück statt 500.
- Mitglied wird gelöscht, das Terminen zugewiesen ist: Termin bleibt bestehen.
- Änderung von Startzeit oder RRULE einer Serie verwirft veraltete Ausnahmen (sonst „Geister“-Absagen).
- Absagen eines Vorkommens, das nicht Teil der Serie ist, wird abgelehnt.

---

## Dateiübersicht

| Datei | Verantwortung |
|---|---|
| `calendar/CalendarPermission.java` | Kalender-Rechte (in `PermissionCatalog` eingetragen) |
| `db/migration/V3__calendar.sql` | Tabellen, Standardrechte für Rolle „Benutzer“ |
| `calendar/CalendarEvent.java`, `CalendarEventException.java`, `CalendarEventRepository.java` | Persistenz |
| `calendar/Occurrence.java`, `RecurrenceExpander.java` | Expansion einzelner Vorkommen |
| `calendar/EventInput.java`, `OccurrenceInput.java`, `CalendarEventService.java`, `EventNotFoundException.java`, `CalendarSettings.java` | Fachlogik, Rechte, Validierung |
| `security/CurrentUser.java` | Aktueller Benutzer als Entity |
| `calendar/CalendarGrid.java`, `DayCell.java` | Raster-/Tagesgruppierung |
| `web/CalendarController.java`, `web/EventForm.java`, `templates/calendar/*` | Weboberfläche |

---

### Task 1: Abhängigkeit, Rechte, Schema und Persistenz

**Files:**
- Modify: `pom.xml`, `security/PermissionCatalog.java`, `src/main/resources/application.yaml`
- Create: `calendar/CalendarPermission.java`, `calendar/CalendarEvent.java`, `calendar/CalendarEventException.java`, `calendar/CalendarEventRepository.java`
- Create: `src/main/resources/db/migration/V3__calendar.sql`
- Test: `src/test/java/club/badernet/nestwerk/calendar/CalendarEventRepositoryTest.java`

**Interfaces:**
- Produces:
  - `CalendarPermission` Werte: `CALENDAR_VIEW`, `CALENDAR_EVENT_CREATE`, `CALENDAR_EVENT_EDIT_OWN`, `CALENDAR_EVENT_EDIT_ALL`, `CALENDAR_EVENT_DELETE_OWN`, `CALENDAR_EVENT_DELETE_ALL`, `CALENDAR_PRIVATE_CREATE`; Modul „Kalender“.
  - `CalendarEvent(String uid, String title, Instant startsAt, Instant endsAt, boolean allDay, String timezone, Long createdBy)`; Getter für alle Felder (`getId`, `getUid`, `getTitle`, `getDescription`, `getLocation`, `getStartsAt`, `getEndsAt`, `isAllDay`, `getTimezone`, `getRrule`, `isPrivateEvent`, `getCreatedBy`, `getSequence`, `getUpdatedAt`, `getMemberIds(): Set<Long>`, `getExceptions(): Set<CalendarEventException>`); Mutatoren `setTitle`, `setDescription`, `setLocation`, `setTimes(Instant, Instant, boolean)`, `setRrule(String)`, `setPrivateEvent(boolean)`, `setMemberIds(Set<Long>)`, `markChanged()`, `clearExceptions()`, `cancelOccurrence(Instant)`, `overrideOccurrence(Instant recurrenceId, String title, String description, String location, Instant startsAt, Instant endsAt)`, `exceptionFor(Instant): Optional<CalendarEventException>`.
  - `CalendarEventException`: `getRecurrenceId()`, `isCancelled()`, `getTitle()`, `getDescription()`, `getLocation()`, `getStartsAt()`, `getEndsAt()` (Overrides nullable).
  - `CalendarEventRepository.findCandidates(Instant from, Instant to, Long viewerId, boolean admin): List<CalendarEvent>`; `findByUid(String)`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarEventRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-12T08:00:00Z");

    @Autowired CalendarEventRepository events;
    @Autowired FamilyMemberRepository members;
    @Autowired jakarta.persistence.EntityManager em;

    private CalendarEvent event(String uid, boolean isPrivate, Long creator) {
        CalendarEvent e = new CalendarEvent(uid, "Titel " + uid, T0, T0.plusSeconds(3600),
                false, "Europe/Berlin", creator);
        e.setPrivateEvent(isPrivate);
        return events.saveAndFlush(e);
    }

    @Test
    void persistsMembersAndExceptions() {
        FamilyMember m = members.save(new FamilyMember("Lena", "#112233", null));
        CalendarEvent e = new CalendarEvent("uid-1", "Serie", T0, T0.plusSeconds(3600),
                false, "Europe/Berlin", 1L);
        e.setRrule("FREQ=WEEKLY");
        e.setMemberIds(Set.of(m.getId()));
        e.cancelOccurrence(T0.plusSeconds(7 * 24 * 3600));
        events.saveAndFlush(e);
        em.clear();

        CalendarEvent loaded = events.findByUid("uid-1").orElseThrow();

        assertThat(loaded.getMemberIds()).containsExactly(m.getId());
        assertThat(loaded.getExceptions()).hasSize(1);
        assertThat(loaded.exceptionFor(T0.plusSeconds(7 * 24 * 3600))).get()
                .extracting(CalendarEventException::isCancelled).isEqualTo(true);
    }

    @Test
    void candidatesHidePrivateEventsOfOthers() {
        event("oeffentlich", false, 1L);
        event("privat-1", true, 1L);
        event("privat-2", true, 2L);
        Instant from = T0.minusSeconds(3600);
        Instant to = T0.plusSeconds(7200);

        List<String> forUser1 = events.findCandidates(from, to, 1L, false).stream()
                .map(CalendarEvent::getUid).toList();
        List<String> forUser3 = events.findCandidates(from, to, 3L, false).stream()
                .map(CalendarEvent::getUid).toList();
        List<String> forAdmin = events.findCandidates(from, to, 3L, true).stream()
                .map(CalendarEvent::getUid).toList();

        assertThat(forUser1).contains("oeffentlich", "privat-1").doesNotContain("privat-2");
        assertThat(forUser3).contains("oeffentlich").doesNotContain("privat-1", "privat-2");
        assertThat(forAdmin).contains("oeffentlich", "privat-1", "privat-2");
    }

    @Test
    void candidatesIncludeRecurringEventsStartedBeforeWindowButNotLaterOnes() {
        CalendarEvent serie = event("serie", false, 1L);
        serie.setRrule("FREQ=DAILY");
        events.saveAndFlush(serie);
        event("einzel-spaeter", false, 1L);

        Instant from = T0.plusSeconds(10 * 24 * 3600);
        Instant to = from.plusSeconds(24 * 3600);

        assertThat(events.findCandidates(from, to, 1L, false)).extracting(CalendarEvent::getUid)
                .contains("serie").doesNotContain("einzel-spaeter");
    }
}
```
(Falls die Testklasse `jakarta.persistence.EntityManager` per `@PersistenceContext` bevorzugt, das Feld entsprechend annotieren; `@Autowired` funktioniert mit Spring Boot ebenfalls.)

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarEventRepositoryTest`
Expected: Kompilierfehler (Klassen fehlen).

- [ ] **Step 3: Implementieren**

`pom.xml` – in `<dependencies>` ergänzen:
```xml
        <dependency>
            <groupId>org.mnode.ical4j</groupId>
            <artifactId>ical4j</artifactId>
            <version>4.4.0</version>
        </dependency>
```

`application.yaml` – unter `nestwerk:` ergänzen:
```yaml
  timezone: ${NESTWERK_TIMEZONE:Europe/Berlin}
```

`calendar/CalendarPermission.java`:
```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.security.Permission;

public enum CalendarPermission implements Permission {
    CALENDAR_VIEW("Kalender ansehen"),
    CALENDAR_EVENT_CREATE("Termine anlegen"),
    CALENDAR_EVENT_EDIT_OWN("Eigene Termine bearbeiten"),
    CALENDAR_EVENT_EDIT_ALL("Alle Termine bearbeiten"),
    CALENDAR_EVENT_DELETE_OWN("Eigene Termine löschen"),
    CALENDAR_EVENT_DELETE_ALL("Alle Termine löschen"),
    CALENDAR_PRIVATE_CREATE("Private Termine anlegen");

    private final String label;

    CalendarPermission(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String module() {
        return "Kalender";
    }
}
```

`PermissionCatalog.collect()` – eine Zeile ergänzen:
```java
        all.addAll(Arrays.asList(club.badernet.nestwerk.calendar.CalendarPermission.values()));
```

`V3__calendar.sql`:
```sql
CREATE TABLE calendar_events (
    id            BIGSERIAL PRIMARY KEY,
    uid           VARCHAR(255)             NOT NULL UNIQUE,
    title         VARCHAR(200)             NOT NULL,
    description   TEXT,
    location      VARCHAR(255),
    starts_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    all_day       BOOLEAN                  NOT NULL DEFAULT FALSE,
    timezone      VARCHAR(64)              NOT NULL DEFAULT 'Europe/Berlin',
    rrule         VARCHAR(500),
    private_event BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_by    BIGINT                   NOT NULL REFERENCES users (id),
    sequence      INTEGER                  NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX calendar_events_starts_at_idx ON calendar_events (starts_at);

CREATE TABLE calendar_event_members (
    event_id  BIGINT NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES family_members (id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, member_id)
);

CREATE TABLE calendar_event_exceptions (
    id            BIGSERIAL PRIMARY KEY,
    event_id      BIGINT                   NOT NULL REFERENCES calendar_events (id) ON DELETE CASCADE,
    recurrence_id TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled     BOOLEAN                  NOT NULL DEFAULT FALSE,
    title         VARCHAR(200),
    description   TEXT,
    location      VARCHAR(255),
    starts_at     TIMESTAMP WITH TIME ZONE,
    ends_at       TIMESTAMP WITH TIME ZONE,
    UNIQUE (event_id, recurrence_id)
);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
         CROSS JOIN (VALUES ('CALENDAR_VIEW'),
                            ('CALENDAR_EVENT_CREATE'),
                            ('CALENDAR_EVENT_EDIT_OWN'),
                            ('CALENDAR_EVENT_DELETE_OWN'),
                            ('CALENDAR_PRIVATE_CREATE')) AS p (permission)
WHERE r.name = 'Benutzer' AND r.system_role;
```

`calendar/CalendarEvent.java`:
```java
package club.badernet.nestwerk.calendar;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 255)
    private String location;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(length = 500)
    private String rrule;

    @Column(name = "private_event", nullable = false)
    private boolean privateEvent;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "calendar_event_members", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "member_id", nullable = false)
    private Set<Long> memberIds = new HashSet<>();

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<CalendarEventException> exceptions = new HashSet<>();

    protected CalendarEvent() {
    }

    public CalendarEvent(String uid, String title, Instant startsAt, Instant endsAt,
                         boolean allDay, String timezone, Long createdBy) {
        this.uid = uid;
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.allDay = allDay;
        this.timezone = timezone;
        this.createdBy = createdBy;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUid() { return uid; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public boolean isAllDay() { return allDay; }
    public String getTimezone() { return timezone; }
    public String getRrule() { return rrule; }
    public boolean isPrivateEvent() { return privateEvent; }
    public Long getCreatedBy() { return createdBy; }
    public int getSequence() { return sequence; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<Long> getMemberIds() { return Set.copyOf(memberIds); }
    public Set<CalendarEventException> getExceptions() { return Set.copyOf(exceptions); }

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setLocation(String location) { this.location = location; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public void setPrivateEvent(boolean privateEvent) { this.privateEvent = privateEvent; }

    public void setTimes(Instant startsAt, Instant endsAt, boolean allDay) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.allDay = allDay;
    }

    public void setMemberIds(Set<Long> ids) {
        memberIds.clear();
        memberIds.addAll(ids);
    }

    /** Erhöht die iCal-SEQUENCE; bei jeder inhaltlichen Änderung aufzurufen. */
    public void markChanged() {
        sequence++;
    }

    public void clearExceptions() {
        exceptions.clear();
    }

    public Optional<CalendarEventException> exceptionFor(Instant recurrenceId) {
        return exceptions.stream().filter(e -> e.getRecurrenceId().equals(recurrenceId)).findFirst();
    }

    public void cancelOccurrence(Instant recurrenceId) {
        CalendarEventException ex = exceptionOrNew(recurrenceId);
        ex.cancel();
    }

    public void overrideOccurrence(Instant recurrenceId, String title, String description,
                                   String location, Instant startsAt, Instant endsAt) {
        CalendarEventException ex = exceptionOrNew(recurrenceId);
        ex.override(title, description, location, startsAt, endsAt);
    }

    private CalendarEventException exceptionOrNew(Instant recurrenceId) {
        return exceptionFor(recurrenceId).orElseGet(() -> {
            CalendarEventException created = new CalendarEventException(this, recurrenceId);
            exceptions.add(created);
            return created;
        });
    }
}
```

`calendar/CalendarEventException.java`:
```java
package club.badernet.nestwerk.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "calendar_event_exceptions")
public class CalendarEventException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private CalendarEvent event;

    @Column(name = "recurrence_id", nullable = false)
    private Instant recurrenceId;

    @Column(nullable = false)
    private boolean cancelled;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 255)
    private String location;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    protected CalendarEventException() {
    }

    CalendarEventException(CalendarEvent event, Instant recurrenceId) {
        this.event = event;
        this.recurrenceId = recurrenceId;
    }

    void cancel() {
        this.cancelled = true;
    }

    void override(String title, String description, String location, Instant startsAt, Instant endsAt) {
        this.cancelled = false;
        this.title = title;
        this.description = description;
        this.location = location;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public Instant getRecurrenceId() { return recurrenceId; }
    public boolean isCancelled() { return cancelled; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
}
```

`calendar/CalendarEventRepository.java`:
```java
package club.badernet.nestwerk.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    Optional<CalendarEvent> findByUid(String uid);

    /**
     * Kandidaten für ein Zeitfenster: Einzeltermine, die das Fenster berühren, und alle Serien,
     * die vor Fensterende beginnen. Private Termine anderer Benutzer sind bereits herausgefiltert.
     */
    @Query("""
            select distinct e from CalendarEvent e
            where e.startsAt < :to
              and (e.rrule is not null or e.endsAt > :from)
              and (e.privateEvent = false or e.createdBy = :viewerId or :admin = true)
            """)
    List<CalendarEvent> findCandidates(@Param("from") Instant from, @Param("to") Instant to,
                                       @Param("viewerId") Long viewerId, @Param("admin") boolean admin);
}
```

- [ ] **Step 4: Test ausführen**

Run: `./mvnw -q test -Dtest=CalendarEventRepositoryTest`
Expected: PASS (3 Tests). Danach `./mvnw -q test -Dtest=PermissionCatalogTest+UserServiceTest` (Katalog erweitert) – PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Kalender-Schema, Entities und Rechte"
```

---

### Task 2: Wiederholungen expandieren

**Files:**
- Create: `calendar/Occurrence.java`, `calendar/RecurrenceExpander.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/RecurrenceExpanderTest.java`

**Interfaces:**
- Consumes: `CalendarEvent` aus Task 1.
- Produces:
  - `record Occurrence(Long eventId, String uid, Instant recurrenceId, String title, String description, String location, ZonedDateTime start, ZonedDateTime end, boolean allDay, boolean privateEvent, boolean recurring, Long createdBy, Set<Long> memberIds)`; `recurrenceId` ist der **ursprüngliche** Beginn des Vorkommens (auch wenn es verschoben wurde).
  - `RecurrenceExpander`: `List<Occurrence> expand(CalendarEvent, Instant from, Instant to)` (Vorkommen, die `[from, to)` berühren, sortiert nach Beginn); `List<Instant> baseStarts(CalendarEvent, Instant from, Instant to)` (Regel-Beginne ohne Ausnahmen, Fenster inklusive); `boolean isBaseOccurrence(CalendarEvent, Instant)`; `void validate(String rrule, ZonedDateTime seed)` wirft `IllegalArgumentException`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceExpanderTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final RecurrenceExpander expander = new RecurrenceExpander();

    private static Instant at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, BERLIN).toInstant();
    }

    private static CalendarEvent event(String rrule, Instant start, Instant end) {
        CalendarEvent e = new CalendarEvent("uid-1", "Test", start, end, false, "Europe/Berlin", 1L);
        e.setRrule(rrule);
        return e;
    }

    private List<Instant> starts(CalendarEvent e, Instant from, Instant to) {
        return expander.expand(e, from, to).stream().map(o -> o.start().toInstant()).toList();
    }

    @Test
    void singleEventInsideWindow() {
        CalendarEvent e = event(null, at(2026, 10, 12, 10, 0), at(2026, 10, 12, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0)))
                .containsExactly(at(2026, 10, 12, 10, 0));
    }

    @Test
    void singleEventOutsideWindowIsIgnored() {
        CalendarEvent e = event(null, at(2026, 12, 1, 10, 0), at(2026, 12, 1, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).isEmpty();
    }

    @Test
    void multiDayEventStartedBeforeWindowOverlaps() {
        CalendarEvent e = event(null, at(2026, 10, 10, 8, 0), at(2026, 10, 14, 8, 0));

        assertThat(starts(e, at(2026, 10, 12, 0, 0), at(2026, 10, 13, 0, 0))).hasSize(1);
    }

    @Test
    void weeklyRecurrence() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 12, 10, 0),
                at(2026, 10, 19, 10, 0), at(2026, 10, 26, 10, 0));
    }

    @Test
    void weeklyWithByDay() {
        CalendarEvent e = event("FREQ=WEEKLY;BYDAY=MO,WE", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 5, 0, 0), at(2026, 10, 19, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 7, 10, 0),
                at(2026, 10, 12, 10, 0), at(2026, 10, 14, 10, 0));
    }

    @Test
    void monthlyOnLastFriday() {
        CalendarEvent e = event("FREQ=MONTHLY;BYDAY=-1FR", at(2026, 10, 30, 18, 0), at(2026, 10, 30, 19, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2027, 1, 1, 0, 0))).containsExactly(
                at(2026, 10, 30, 18, 0), at(2026, 11, 27, 18, 0), at(2026, 12, 25, 18, 0));
    }

    @Test
    void everySecondWeek() {
        CalendarEvent e = event("FREQ=WEEKLY;INTERVAL=2", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 8, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 19, 10, 0), at(2026, 11, 2, 10, 0));
    }

    @Test
    void countLimitsOccurrences() {
        CalendarEvent e = event("FREQ=DAILY;COUNT=3", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 1, 1, 0, 0), at(2027, 1, 1, 0, 0))).hasSize(3);
    }

    @Test
    void untilLimitsOccurrences() {
        CalendarEvent e = event("FREQ=DAILY;UNTIL=20261008T235959Z",
                at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).hasSize(4);
    }

    @Test
    void untilInThePastYieldsNothingInLaterWindow() {
        CalendarEvent e = event("FREQ=DAILY;UNTIL=20261008T235959Z",
                at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 11, 1, 0, 0), at(2026, 12, 1, 0, 0))).isEmpty();
    }

    @Test
    void cancelledOccurrenceIsSkipped() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));
        e.cancelOccurrence(at(2026, 10, 12, 10, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 19, 10, 0), at(2026, 10, 26, 10, 0));
    }

    @Test
    void movedOccurrenceUsesOverrideButKeepsRecurrenceId() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));
        e.overrideOccurrence(at(2026, 10, 12, 10, 0), "Verschoben", null, null,
                at(2026, 10, 13, 15, 0), at(2026, 10, 13, 16, 0));

        List<Occurrence> result = expander.expand(e, at(2026, 10, 10, 0, 0), at(2026, 10, 15, 0, 0));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Verschoben");
        assertThat(result.getFirst().start().toInstant()).isEqualTo(at(2026, 10, 13, 15, 0));
        assertThat(result.getFirst().recurrenceId()).isEqualTo(at(2026, 10, 12, 10, 0));
    }

    @Test
    void localStartTimeSurvivesDaylightSavingChange() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 3, 21, 10, 0), at(2026, 3, 21, 11, 0));

        List<Occurrence> result = expander.expand(e, at(2026, 3, 1, 0, 0), at(2026, 4, 30, 0, 0));

        assertThat(result).hasSize(6);
        assertThat(result).allSatisfy(o -> assertThat(o.start().withZoneSameInstant(BERLIN).getHour()).isEqualTo(10));
    }

    @Test
    void allDayFlagIsKept() {
        CalendarEvent e = new CalendarEvent("uid-2", "Ganztag", at(2026, 10, 12, 0, 0),
                at(2026, 10, 13, 0, 0), true, "Europe/Berlin", 1L);
        e.setRrule("FREQ=WEEKLY");

        List<Occurrence> result = expander.expand(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0));

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(o -> {
            assertThat(o.allDay()).isTrue();
            assertThat(o.start().getHour()).isZero();
        });
    }

    @Test
    void isBaseOccurrenceDistinguishesRuleDatesFromOthers() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(expander.isBaseOccurrence(e, at(2026, 10, 12, 10, 0))).isTrue();
        assertThat(expander.isBaseOccurrence(e, at(2026, 10, 13, 10, 0))).isFalse();
    }

    @Test
    void invalidRuleIsRejected() {
        ZonedDateTime seed = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, BERLIN);

        assertThatThrownBy(() -> expander.validate("FREQ=SOMETIMES", seed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expander.validate("das ist keine regel", seed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validRuleIsAccepted() {
        ZonedDateTime seed = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, BERLIN);

        expander.validate("FREQ=MONTHLY;BYDAY=-1FR", seed);
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=RecurrenceExpanderTest`
Expected: Kompilierfehler (`RecurrenceExpander`, `Occurrence` fehlen).

- [ ] **Step 3: Implementieren**

`calendar/Occurrence.java`:
```java
package club.badernet.nestwerk.calendar;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;

/** Ein konkretes Vorkommen eines Termins (bei Serien eines von vielen). */
public record Occurrence(Long eventId, String uid, Instant recurrenceId, String title,
                         String description, String location, ZonedDateTime start, ZonedDateTime end,
                         boolean allDay, boolean privateEvent, boolean recurring, Long createdBy,
                         Set<Long> memberIds) {
}
```

`calendar/RecurrenceExpander.java`:
```java
package club.badernet.nestwerk.calendar;

import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Component
public class RecurrenceExpander {

    public void validate(String rrule, ZonedDateTime seed) {
        try {
            new Recur<ZonedDateTime>(rrule).getDates(seed, seed, seed.plusYears(2));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Die Wiederholungsregel ist ungültig.", e);
        }
    }

    /** Beginne laut Regel (ohne Ausnahmen) im Fenster; Fenstergrenzen inklusive. */
    public List<Instant> baseStarts(CalendarEvent event, Instant from, Instant to) {
        Set<Instant> starts = new TreeSet<>();
        Instant seedInstant = event.getStartsAt();
        if (event.getRrule() == null) {
            if (!seedInstant.isBefore(from) && !seedInstant.isAfter(to)) {
                starts.add(seedInstant);
            }
        } else if (!to.isBefore(seedInstant)) {
            ZoneId zone = ZoneId.of(event.getTimezone());
            ZonedDateTime seed = seedInstant.atZone(zone);
            new Recur<ZonedDateTime>(event.getRrule())
                    .getDates(seed, from.atZone(zone), to.atZone(zone))
                    .forEach(d -> starts.add(d.toInstant()));
            if (!seedInstant.isBefore(from) && !seedInstant.isAfter(to)) {
                starts.add(seedInstant);
            }
        }
        return List.copyOf(starts);
    }

    public boolean isBaseOccurrence(CalendarEvent event, Instant start) {
        return baseStarts(event, start.minusSeconds(1), start.plusSeconds(1)).contains(start);
    }

    public List<Occurrence> expand(CalendarEvent event, Instant from, Instant to) {
        ZoneId zone = ZoneId.of(event.getTimezone());
        Duration length = Duration.between(event.getStartsAt(), event.getEndsAt());
        List<Occurrence> result = new ArrayList<>();
        for (Instant original : baseStarts(event, from.minus(length), to)) {
            Optional<CalendarEventException> exception = event.exceptionFor(original);
            if (exception.isPresent() && exception.get().isCancelled()) {
                continue;
            }
            Instant start = original;
            Instant end = original.plus(length);
            String title = event.getTitle();
            String description = event.getDescription();
            String location = event.getLocation();
            if (exception.isPresent()) {
                CalendarEventException ex = exception.get();
                if (ex.getStartsAt() != null) {
                    start = ex.getStartsAt();
                    end = start.plus(length);
                }
                if (ex.getEndsAt() != null) {
                    end = ex.getEndsAt();
                }
                title = ex.getTitle() != null ? ex.getTitle() : title;
                description = ex.getDescription() != null ? ex.getDescription() : description;
                location = ex.getLocation() != null ? ex.getLocation() : location;
            }
            if (end.isAfter(from) && start.isBefore(to)) {
                result.add(new Occurrence(event.getId(), event.getUid(), original, title, description,
                        location, start.atZone(zone), end.atZone(zone), event.isAllDay(), event.isPrivateEvent(),
                        event.getRrule() != null, event.getCreatedBy(), event.getMemberIds()));
            }
        }
        result.sort(Comparator.comparing(Occurrence::start));
        return result;
    }
}
```

- [ ] **Step 4: Test ausführen**

Run: `./mvnw -q test -Dtest=RecurrenceExpanderTest`
Expected: PASS (16 Tests).
Falls ein Test wegen ical4j-Verhalten abweicht (z. B. ob `getDates` den Seed selbst liefert oder `UNTIL` inklusiv ist): Ursache am echten Ergebnis klären und die Implementierung anpassen, nicht die Erwartung abschwächen — alle Erwartungen folgen RFC 5545.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Wiederholungsregeln mit ical4j expandieren"
```

---

### Task 3: Termin-Service mit Rechten und Sichtbarkeit

**Files:**
- Create: `security/CurrentUser.java`
- Create: `calendar/CalendarSettings.java`, `calendar/EventInput.java`, `calendar/OccurrenceInput.java`, `calendar/EventNotFoundException.java`, `calendar/CalendarEventService.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/CalendarEventServiceTest.java`

**Interfaces:**
- Consumes: `CalendarEventRepository`, `RecurrenceExpander`, `FamilyMemberRepository`, `User.permissionNames()/isAdministrator()`, `CalendarPermission`.
- Produces:
  - `CurrentUser.require(): User` (wirft `AccessDeniedException`).
  - `CalendarSettings.zone(): ZoneId`.
  - `record EventInput(String title, String description, String location, LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime, boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds)`.
  - `record OccurrenceInput(String title, String description, String location, LocalDateTime start, LocalDateTime end)`.
  - `CalendarEventService`: `findOccurrences(Instant from, Instant to, User viewer): List<Occurrence>`, `get(Long id, User viewer): CalendarEvent` (wirft `EventNotFoundException`), `create(EventInput, User): CalendarEvent`, `update(Long, EventInput, User)`, `delete(Long, User)`, `cancelOccurrence(Long id, Instant recurrenceId, User)`, `overrideOccurrence(Long id, Instant recurrenceId, OccurrenceInput, User)`, `canEdit(CalendarEvent, User): boolean`, `canDelete(CalendarEvent, User): boolean`. Validierungsfehler als `IllegalArgumentException` (deutsch), Rechtefehler als `AccessDeniedException`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarEventServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired CalendarEventService service;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User owner;
    User other;
    User admin;
    User viewerOnly;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Normal", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN", "CALENDAR_PRIVATE_CREATE"));
        Role readOnly = roleService.create("Nur lesen", null, Set.of("CALENDAR_VIEW"));
        owner = userService.create("besitzer", "passwort1", Set.of(normal.getId()));
        other = userService.create("anderer", "passwort1", Set.of(normal.getId()));
        viewerOnly = userService.create("leser", "passwort1", Set.of(readOnly.getId()));
        admin = userService.createAdministrator("chef", "passwort1");
    }

    private static EventInput timed(String title, boolean isPrivate) {
        return new EventInput(title, null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, isPrivate, Set.of());
    }

    private static Instant day(int month, int day) {
        return LocalDate.of(2026, month, day).atStartOfDay(BERLIN).toInstant();
    }

    private List<String> titles(User viewer) {
        return service.findOccurrences(day(10, 1), day(11, 1), viewer).stream().map(Occurrence::title).toList();
    }

    @Test
    void createsTimedEventInConfiguredZone() {
        CalendarEvent e = service.create(timed("Arzt", false), owner);

        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-10-12T08:00:00Z"));
        assertThat(e.getEndsAt()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(e.getUid()).endsWith("@nestwerk");
    }

    @Test
    void allDayEventEndsAtStartOfDayAfterLastDay() {
        EventInput in = new EventInput("Urlaub", null, null, LocalDate.of(2026, 10, 12), null,
                LocalDate.of(2026, 10, 14), null, true, null, false, Set.of());

        CalendarEvent e = service.create(in, owner);

        assertThat(e.getStartsAt()).isEqualTo(day(10, 12));
        assertThat(e.getEndsAt()).isEqualTo(day(10, 15));
    }

    @Test
    void rejectsEndNotAfterStart() {
        EventInput in = new EventInput("Falsch", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(10, 0), false, null, false, Set.of());

        assertThatThrownBy(() -> service.create(in, owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ende");
    }

    @Test
    void rejectsAllDayEndBeforeStart() {
        EventInput in = new EventInput("Falsch", null, null, LocalDate.of(2026, 10, 12), null,
                LocalDate.of(2026, 10, 11), null, true, null, false, Set.of());

        assertThatThrownBy(() -> service.create(in, owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankTitleAndInvalidRule() {
        assertThatThrownBy(() -> service.create(timed(" ", false), owner))
                .isInstanceOf(IllegalArgumentException.class);
        EventInput bad = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, "FREQ=IRGENDWANN", false, Set.of());
        assertThatThrownBy(() -> service.create(bad, owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsRulePrefixedWithRRULE() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, "RRULE:FREQ=WEEKLY", false, Set.of());

        assertThat(service.create(in, owner).getRrule()).isEqualTo("FREQ=WEEKLY");
    }

    @Test
    void rejectsUnknownMember() {
        EventInput in = new EventInput("Mit Geist", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(999_999L));

        assertThatThrownBy(() -> service.create(in, owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignsMembers() {
        FamilyMember m = memberService.create("Lena", "#112233", null);
        EventInput in = new EventInput("Mit Lena", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(m.getId()));

        assertThat(service.create(in, owner).getMemberIds()).containsExactly(m.getId());
    }

    @Test
    void privateEventsAreOnlyVisibleToCreatorAndAdministrator() {
        service.create(timed("Geheim", true), owner);
        service.create(timed("Offen", false), owner);

        assertThat(titles(owner)).contains("Geheim", "Offen");
        assertThat(titles(admin)).contains("Geheim", "Offen");
        assertThat(titles(other)).contains("Offen").doesNotContain("Geheim");
        assertThat(titles(viewerOnly)).contains("Offen").doesNotContain("Geheim");
    }

    @Test
    void directAccessToForeignPrivateEventLooksLikeNotFound() {
        CalendarEvent e = service.create(timed("Geheim", true), owner);

        assertThatThrownBy(() -> service.get(e.getId(), other)).isInstanceOf(EventNotFoundException.class);
        assertThat(service.get(e.getId(), admin)).isNotNull();
    }

    @Test
    void creatingRequiresPermission() {
        assertThatThrownBy(() -> service.create(timed("Nein", false), viewerOnly))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void privateCreationRequiresItsOwnPermission() {
        Role noPrivate = roleService.create("Ohne privat", null,
                Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE"));
        User user = userService.create("ohne-privat", "passwort1", Set.of(noPrivate.getId()));

        assertThatThrownBy(() -> service.create(timed("Privat", true), user))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownEventsEditableForeignOnesNot() {
        CalendarEvent e = service.create(timed("Meins", false), owner);

        service.update(e.getId(), timed("Geändert", false), owner);

        assertThatThrownBy(() -> service.update(e.getId(), timed("Hack", false), other))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(e.getId(), other)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.get(e.getId(), owner).getTitle()).isEqualTo("Geändert");
    }

    @Test
    void administratorMayEditAndDeleteAnything() {
        CalendarEvent e = service.create(timed("Fremd", false), owner);

        service.update(e.getId(), timed("Vom Admin", false), admin);
        service.delete(e.getId(), admin);

        assertThatThrownBy(() -> service.get(e.getId(), admin)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void updateIncrementsSequence() {
        CalendarEvent e = service.create(timed("V1", false), owner);
        int before = e.getSequence();

        service.update(e.getId(), timed("V2", false), owner);

        assertThat(service.get(e.getId(), owner).getSequence()).isGreaterThan(before);
    }

    @Test
    void cancellingOccurrenceHidesOnlyThatOne() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(in, owner);
        Instant second = LocalDate.of(2026, 10, 12).atTime(10, 0).atZone(BERLIN).toInstant();

        service.cancelOccurrence(e.getId(), second, owner);

        assertThat(service.findOccurrences(day(10, 1), day(11, 1), owner)).hasSize(3);
    }

    @Test
    void cancellingNonOccurrenceIsRejected() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(in, owner);
        Instant notInSeries = LocalDate.of(2026, 10, 13).atTime(10, 0).atZone(BERLIN).toInstant();

        assertThatThrownBy(() -> service.cancelOccurrence(e.getId(), notInSeries, owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changingRuleDropsStaleExceptions() {
        EventInput weekly = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(weekly, owner);
        service.cancelOccurrence(e.getId(),
                LocalDate.of(2026, 10, 12).atTime(10, 0).atZone(BERLIN).toInstant(), owner);
        EventInput daily = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=DAILY", false, Set.of());

        service.update(e.getId(), daily, owner);

        assertThat(service.get(e.getId(), owner).getExceptions()).isEmpty();
    }

    @Test
    void windowLargerThanFourHundredDaysIsRejected() {
        assertThatThrownBy(() -> service.findOccurrences(day(1, 1),
                LocalDate.of(2028, 1, 1).atStartOfDay(BERLIN).toInstant(), owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewingRequiresPermission() {
        Role none = roleService.create("Nichts", null, Set.of());
        User nobody = userService.create("niemand", "passwort1", Set.of(none.getId()));

        assertThatThrownBy(() -> service.findOccurrences(day(10, 1), day(11, 1), nobody))
                .isInstanceOf(AccessDeniedException.class);
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarEventServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`security/CurrentUser.java`:
```java
package club.badernet.nestwerk.security;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Nicht angemeldet");
        }
        return users.findByUsername(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Benutzer unbekannt"));
    }
}
```

`calendar/CalendarSettings.java`:
```java
package club.badernet.nestwerk.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class CalendarSettings {

    private final ZoneId zone;

    public CalendarSettings(@Value("${nestwerk.timezone:Europe/Berlin}") String timezone) {
        this.zone = ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return zone;
    }
}
```

`calendar/EventInput.java`:
```java
package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** Eingabe für Anlegen/Ändern. Bei ganztägigen Terminen ist {@code endDate} inklusiv. */
public record EventInput(String title, String description, String location,
                         LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                         boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds) {
}
```

`calendar/OccurrenceInput.java`:
```java
package club.badernet.nestwerk.calendar;

import java.time.LocalDateTime;

public record OccurrenceInput(String title, String description, String location,
                              LocalDateTime start, LocalDateTime end) {
}
```

`calendar/EventNotFoundException.java`:
```java
package club.badernet.nestwerk.calendar;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException() {
        super("Termin nicht gefunden");
    }
}
```

`calendar/CalendarEventService.java`:
```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.member.FamilyMemberRepository;
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

    private final CalendarEventRepository events;
    private final FamilyMemberRepository members;
    private final RecurrenceExpander expander;
    private final ZoneId zone;

    public CalendarEventService(CalendarEventRepository events, FamilyMemberRepository members,
                                RecurrenceExpander expander, CalendarSettings settings) {
        this.events = events;
        this.members = members;
        this.expander = expander;
        this.zone = settings.zone();
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
        event.overrideOccurrence(recurrenceId, requireTitle(in.title()), clean(in.description()),
                clean(in.location()), start, end);
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
        event.setDescription(clean(in.description()));
        event.setLocation(clean(in.location()));
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

    private String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private boolean isVisible(CalendarEvent event, User viewer) {
        return !event.isPrivateEvent()
                || event.getCreatedBy().equals(viewer.getId())
                || viewer.isAdministrator();
    }

    private boolean has(User user, CalendarPermission permission) {
        return user.permissionNames().contains(permission.name());
    }

    private void require(User user, CalendarPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung: " + permission.label());
        }
    }
}
```

- [ ] **Step 4: Test ausführen**

Run: `./mvnw -q test -Dtest=CalendarEventServiceTest`
Expected: PASS (19 Tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Termin-Service mit Rechteprüfung und privaten Terminen"
```

---

### Task 4: Raster- und Tageslogik

**Files:**
- Create: `calendar/CalendarGrid.java`, `calendar/DayCell.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/CalendarGridTest.java`

**Interfaces:**
- Consumes: `Occurrence`.
- Produces: `CalendarGrid.monthDays(YearMonth): List<LocalDate>` (Montag-erste Wochen, volle Wochen), `weekDays(LocalDate): List<LocalDate>` (Mo–So der Woche), `groupByDay(List<Occurrence>, List<LocalDate>): Map<LocalDate, List<Occurrence>>` (jeder Tag der Liste ist Schlüssel; Mehrtagestermine in jedem berührten Tag; Ende exklusiv); `record DayCell(LocalDate date, boolean inMonth, boolean today, List<Occurrence> occurrences)`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarGridTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Occurrence occ(String title, ZonedDateTime start, ZonedDateTime end, boolean allDay) {
        return new Occurrence(1L, "u", start.toInstant(), title, null, null, start, end,
                allDay, false, false, 1L, Set.of());
    }

    private static ZonedDateTime z(int m, int d, int h, int min) {
        return ZonedDateTime.of(2026, m, d, h, min, 0, 0, BERLIN);
    }

    @Test
    void monthGridStartsOnMondayAndEndsOnSunday() {
        List<LocalDate> days = CalendarGrid.monthDays(YearMonth.of(2026, 10));

        assertThat(days.getFirst()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(days.getFirst().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(days.getLast()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(days).hasSize(35);
    }

    @Test
    void monthStartingOnMondayHasNoLeadingDays() {
        List<LocalDate> days = CalendarGrid.monthDays(YearMonth.of(2026, 6));

        assertThat(days.getFirst()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void weekDaysAreMondayToSunday() {
        List<LocalDate> days = CalendarGrid.weekDays(LocalDate.of(2026, 10, 14));

        assertThat(days).hasSize(7);
        assertThat(days.getFirst()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(days.getLast()).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void eventOverMidnightAppearsOnBothDays() {
        Occurrence o = occ("Nachtschicht", z(10, 31, 23, 30), z(11, 1, 0, 30), false);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(o),
                List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)));

        assertThat(grouped.get(LocalDate.of(2026, 10, 31))).containsExactly(o);
        assertThat(grouped.get(LocalDate.of(2026, 11, 1))).containsExactly(o);
        assertThat(grouped.get(LocalDate.of(2026, 11, 2))).isEmpty();
    }

    @Test
    void allDayEventEndingAtMidnightDoesNotSpillIntoNextDay() {
        Occurrence o = occ("Geburtstag", z(10, 12, 0, 0), z(10, 13, 0, 0), true);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(o),
                List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)));

        assertThat(grouped.get(LocalDate.of(2026, 10, 12))).containsExactly(o);
        assertThat(grouped.get(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void multiDayEventAppearsOnEveryDay() {
        Occurrence o = occ("Urlaub", z(10, 12, 0, 0), z(10, 15, 0, 0), true);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(o),
                CalendarGrid.weekDays(LocalDate.of(2026, 10, 12)));

        assertThat(grouped.values().stream().filter(l -> !l.isEmpty()).count()).isEqualTo(3);
    }

    @Test
    void occurrencesPerDayAreSortedByStart() {
        Occurrence late = occ("Spät", z(10, 12, 18, 0), z(10, 12, 19, 0), false);
        Occurrence early = occ("Früh", z(10, 12, 7, 0), z(10, 12, 8, 0), false);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(late, early),
                List.of(LocalDate.of(2026, 10, 12)));

        assertThat(grouped.get(LocalDate.of(2026, 10, 12))).containsExactly(early, late);
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarGridTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`calendar/DayCell.java`:
```java
package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.util.List;

public record DayCell(LocalDate date, boolean inMonth, boolean today, List<Occurrence> occurrences) {
}
```

`calendar/CalendarGrid.java`:
```java
package club.badernet.nestwerk.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CalendarGrid {

    private CalendarGrid() {
    }

    public static List<LocalDate> monthDays(YearMonth month) {
        LocalDate start = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return start.datesUntil(end.plusDays(1)).toList();
    }

    public static List<LocalDate> weekDays(LocalDate any) {
        LocalDate monday = any.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.datesUntil(monday.plusDays(7)).toList();
    }

    public static Map<LocalDate, List<Occurrence>> groupByDay(List<Occurrence> occurrences,
                                                              List<LocalDate> days) {
        Map<LocalDate, List<Occurrence>> grouped = new LinkedHashMap<>();
        days.forEach(d -> grouped.put(d, new ArrayList<>()));
        for (Occurrence o : occurrences) {
            LocalDate first = o.start().toLocalDate();
            LocalDate last = o.end().minusNanos(1).toLocalDate();
            for (LocalDate day : days) {
                if (!day.isBefore(first) && !day.isAfter(last)) {
                    grouped.get(day).add(o);
                }
            }
        }
        grouped.values().forEach(l -> l.sort(Comparator.comparing(Occurrence::start)));
        return grouped;
    }
}
```

- [ ] **Step 4: Test ausführen**

Run: `./mvnw -q test -Dtest=CalendarGridTest`
Expected: PASS (7 Tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Kalenderraster und Tagesgruppierung"
```

---

### Task 5: Ansichten (Monat, Woche, Agenda) und Navigation

**Files:**
- Create: `web/CalendarController.java`
- Create: `templates/calendar/month.html`, `templates/calendar/week.html`, `templates/calendar/agenda.html`, `templates/calendar/fragments.html`
- Modify: `templates/fragments/layout.html` (Menüpunkt), `static/css/nestwerk.css`
- Test: `src/test/java/club/badernet/nestwerk/web/CalendarViewTests.java`

**Interfaces:**
- Consumes: `CalendarEventService.findOccurrences`, `CalendarGrid`, `CurrentUser.require()`, `FamilyMemberService.findAll()`, `CalendarSettings.zone()`.
- Produces: Routen `GET /calendar` (→ Monat), `/calendar/month?date=YYYY-MM-DD`, `/calendar/week?date=…`, `/calendar/agenda?from=…`; Modellattribute `weeks`/`days`, `memberById`, `prev`, `next`, `today`. Das Fragment `calendar/fragments :: occurrence(o)` rendert ein Vorkommen mit Link zu `/calendar/events/{id}?occurrence=…` (Ziel in Task 6).

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarViewTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired CalendarEventService events;

    User owner;
    User other;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Kalendernutzer", null, Set.of("CALENDAR_VIEW",
                "CALENDAR_EVENT_CREATE", "CALENDAR_PRIVATE_CREATE", "CALENDAR_EVENT_EDIT_OWN"));
        owner = userService.create("kal-besitzer", "passwort1", Set.of(normal.getId()));
        other = userService.create("kal-anderer", "passwort1", Set.of(normal.getId()));
        events.create(new EventInput("Zahnarzt", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of()), owner);
        events.create(new EventInput("Geheimtermin", null, null, LocalDate.of(2026, 10, 13), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 13), LocalTime.of(11, 0), false, null, true, Set.of()), owner);
    }

    @Test
    void monthViewShowsEventsForOwner() throws Exception {
        mockMvc.perform(get("/calendar/month").param("date", "2026-10-15")
                        .with(user("kal-besitzer").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahnarzt")))
                .andExpect(content().string(containsString("Geheimtermin")));
    }

    @Test
    void privateEventIsHiddenFromOtherUserInAllViews() throws Exception {
        var auth = new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW");
        for (String path : new String[]{"/calendar/month", "/calendar/week", "/calendar/agenda"}) {
            var param = path.endsWith("agenda") ? "from" : "date";
            mockMvc.perform(get(path).param(param, "2026-10-12").with(user("kal-anderer").authorities(auth)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Zahnarzt")))
                    .andExpect(content().string(not(containsString("Geheimtermin"))));
        }
    }

    @Test
    void invalidDateFallsBackToToday() throws Exception {
        mockMvc.perform(get("/calendar/month").param("date", "abc")
                        .with(user("kal-besitzer").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isOk());
    }

    @Test
    void forbiddenWithoutViewPermission() throws Exception {
        mockMvc.perform(get("/calendar/month").with(user("kal-besitzer").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("MEMBER_MANAGE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void calendarRedirectsToMonth() throws Exception {
        mockMvc.perform(get("/calendar").with(user("kal-besitzer").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().is3xxRedirection());
    }
}
```
Hinweis: `CurrentUser` löst den Benutzer per Name aus der DB auf; die Authorities im Request-Postprozessor steuern nur den URL-/Methodenzugriff, die Fachprüfung nutzt die DB-Rechte der Rolle „Kalendernutzer“.

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarViewTests`
Expected: FAIL (404, Controller fehlt).

- [ ] **Step 3: Implementieren**

`web/CalendarController.java` (Ansichten; Termin-Formulare folgen in Task 6 in derselben Klasse):
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarGrid;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.DayCell;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarController {

    private static final int AGENDA_DAYS = 60;

    private final CalendarEventService events;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings settings;

    public CalendarController(CalendarEventService events, FamilyMemberService members,
                              CurrentUser currentUser, CalendarSettings settings) {
        this.events = events;
        this.members = members;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    @GetMapping
    String index() {
        return "redirect:/calendar/month";
    }

    @GetMapping("/month")
    String month(@RequestParam(required = false) String date, Model model) {
        LocalDate day = parseDate(date);
        YearMonth month = YearMonth.from(day);
        List<LocalDate> days = CalendarGrid.monthDays(month);
        Map<LocalDate, List<Occurrence>> byDay = load(days);
        LocalDate today = LocalDate.now(settings.zone());

        List<List<DayCell>> weeks = new ArrayList<>();
        for (int i = 0; i < days.size(); i += 7) {
            List<DayCell> week = new ArrayList<>();
            for (LocalDate d : days.subList(i, i + 7)) {
                week.add(new DayCell(d, YearMonth.from(d).equals(month), d.equals(today), byDay.get(d)));
            }
            weeks.add(week);
        }
        model.addAttribute("weeks", weeks);
        model.addAttribute("month", month);
        model.addAttribute("prev", month.minusMonths(1).atDay(1));
        model.addAttribute("next", month.plusMonths(1).atDay(1));
        addCommon(model, "month");
        return "calendar/month";
    }

    @GetMapping("/week")
    String week(@RequestParam(required = false) String date, Model model) {
        LocalDate day = parseDate(date);
        List<LocalDate> days = CalendarGrid.weekDays(day);
        Map<LocalDate, List<Occurrence>> byDay = load(days);
        LocalDate today = LocalDate.now(settings.zone());

        List<DayCell> cells = days.stream()
                .map(d -> new DayCell(d, true, d.equals(today), byDay.get(d))).toList();
        model.addAttribute("days", cells);
        model.addAttribute("first", days.getFirst());
        model.addAttribute("last", days.getLast());
        model.addAttribute("prev", days.getFirst().minusWeeks(1));
        model.addAttribute("next", days.getFirst().plusWeeks(1));
        addCommon(model, "week");
        return "calendar/week";
    }

    @GetMapping("/agenda")
    String agenda(@RequestParam(required = false) String from, Model model) {
        LocalDate start = parseDate(from);
        List<LocalDate> days = start.datesUntil(start.plusDays(AGENDA_DAYS)).toList();
        Map<LocalDate, List<Occurrence>> byDay = load(days);
        Map<LocalDate, List<Occurrence>> nonEmpty = new LinkedHashMap<>();
        byDay.forEach((d, l) -> {
            if (!l.isEmpty()) {
                nonEmpty.put(d, l);
            }
        });
        model.addAttribute("agenda", nonEmpty);
        model.addAttribute("from", start);
        model.addAttribute("prev", start.minusDays(AGENDA_DAYS));
        model.addAttribute("next", start.plusDays(AGENDA_DAYS));
        addCommon(model, "agenda");
        return "calendar/agenda";
    }

    private Map<LocalDate, List<Occurrence>> load(List<LocalDate> days) {
        User viewer = currentUser.require();
        Instant from = days.getFirst().atStartOfDay(settings.zone()).toInstant();
        Instant to = days.getLast().plusDays(1).atStartOfDay(settings.zone()).toInstant();
        return CalendarGrid.groupByDay(events.findOccurrences(from, to, viewer), days);
    }

    private void addCommon(Model model, String view) {
        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        members.findAll().forEach(m -> byId.put(m.getId(), m));
        model.addAttribute("memberById", byId);
        model.addAttribute("view", view);
        model.addAttribute("today", LocalDate.now(settings.zone()));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now(settings.zone());
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return LocalDate.now(settings.zone());
        }
    }
}
```

`templates/calendar/fragments.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="viewswitch(view, date)" class="viewswitch">
    <a th:href="@{/calendar/month(date=${date})}" th:classappend="${view == 'month'} ? 'active'">Monat</a>
    <a th:href="@{/calendar/week(date=${date})}" th:classappend="${view == 'week'} ? 'active'">Woche</a>
    <a th:href="@{/calendar/agenda(from=${date})}" th:classappend="${view == 'agenda'} ? 'active'">Agenda</a>
    <a th:href="@{/calendar/events/new(date=${date})}" sec:authorize="hasAuthority('CALENDAR_EVENT_CREATE')"
       xmlns:sec="http://www.thymeleaf.org/extras/spring-security">+ Termin</a>
</div>

<span th:fragment="occurrence(o)" class="occ">
    <span th:each="mid : ${o.memberIds}" class="dot"
          th:style="'background:' + ${memberById[mid] != null ? memberById[mid].color : '#999'}"></span>
    <span th:unless="${o.allDay}" th:text="${#temporals.format(o.start, 'HH:mm')}"></span>
    <a th:href="@{/calendar/events/{id}(id=${o.eventId}, occurrence=${o.recurring} ? ${o.recurrenceId} : null)}"
       th:text="${(o.privateEvent ? '🔒 ' : '') + o.title}"></a>
</span>
</body>
</html>
```

`templates/calendar/month.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('Kalender')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1 th:text="${#temporals.format(month.atDay(1), 'MMMM yyyy')}">Oktober 2026</h1>
<div th:replace="~{calendar/fragments :: viewswitch('month', ${month.atDay(1)})}"></div>
<p>
    <a th:href="@{/calendar/month(date=${prev})}">« zurück</a>
    <a th:href="@{/calendar/month(date=${today})}">Heute</a>
    <a th:href="@{/calendar/month(date=${next})}">weiter »</a>
</p>
<table class="month">
    <thead><tr><th>Mo</th><th>Di</th><th>Mi</th><th>Do</th><th>Fr</th><th>Sa</th><th>So</th></tr></thead>
    <tbody>
    <tr th:each="week : ${weeks}">
        <td th:each="cell : ${week}"
            th:classappend="${(cell.inMonth ? '' : 'outside ') + (cell.today ? 'today' : '')}">
            <div class="daynum"><a th:href="@{/calendar/week(date=${cell.date})}"
                                   th:text="${cell.date.dayOfMonth}">1</a></div>
            <div th:each="o : ${cell.occurrences}" th:replace="~{calendar/fragments :: occurrence(${o})}"></div>
        </td>
    </tr>
    </tbody>
</table>
</main>
</body>
</html>
```
(Die Fragment-Datei `fragments.html` erklärt `xmlns:sec` lokal; alternativ im `<html>`-Tag deklarieren.)

`templates/calendar/week.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('Kalender – Woche')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Woche ab <span th:text="${#temporals.format(first, 'dd.MM.yyyy')}"></span></h1>
<div th:replace="~{calendar/fragments :: viewswitch('week', ${first})}"></div>
<p>
    <a th:href="@{/calendar/week(date=${prev})}">« zurück</a>
    <a th:href="@{/calendar/week(date=${today})}">Heute</a>
    <a th:href="@{/calendar/week(date=${next})}">weiter »</a>
</p>
<section th:each="cell : ${days}" class="weekday" th:classappend="${cell.today} ? 'today'">
    <h2 th:text="${#temporals.format(cell.date, 'EEEE, dd.MM.')}"></h2>
    <p th:if="${cell.occurrences.isEmpty()}" class="muted">Keine Termine</p>
    <div th:each="o : ${cell.occurrences}" th:replace="~{calendar/fragments :: occurrence(${o})}"></div>
</section>
</main>
</body>
</html>
```

`templates/calendar/agenda.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('Kalender – Agenda')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Agenda</h1>
<div th:replace="~{calendar/fragments :: viewswitch('agenda', ${from})}"></div>
<p>
    <a th:href="@{/calendar/agenda(from=${prev})}">« früher</a>
    <a th:href="@{/calendar/agenda(from=${today})}">Heute</a>
    <a th:href="@{/calendar/agenda(from=${next})}">später »</a>
</p>
<p th:if="${agenda.isEmpty()}" class="muted">Keine Termine in diesem Zeitraum.</p>
<section th:each="entry : ${agenda}">
    <h2 th:text="${#temporals.format(entry.key, 'EEEE, dd.MM.yyyy')}"></h2>
    <div th:each="o : ${entry.value}" th:replace="~{calendar/fragments :: occurrence(${o})}"></div>
</section>
</main>
</body>
</html>
```

`layout.html` – im `nav` vor „Benutzer“ ergänzen:
```html
    <a th:href="@{/calendar}" sec:authorize="hasAuthority('CALENDAR_VIEW')">Kalender</a>
```

`nestwerk.css` ergänzen:
```css
.viewswitch a { margin-right: .75rem; }
.viewswitch a.active { font-weight: bold; }
table.month td { width: 14%; height: 6rem; border: 1px solid #ddd; }
table.month td.outside { background: #f5f5f5; color: #888; }
table.month td.today, section.today { background: #fff8d6; }
.dot { display: inline-block; width: .6rem; height: .6rem; border-radius: 50%; margin-right: .2rem; }
.occ { display: block; font-size: .9rem; }
.muted { color: #777; }
```

- [ ] **Step 4: Test ausführen**

Run: `./mvnw -q test -Dtest=CalendarViewTests`
Expected: PASS (5 Tests). Bei Thymeleaf-Fehlern in `th:replace` mit Fragment-Parametern die Fragment-Signatur prüfen (`occurrence(o)` erwartet ein `Occurrence`).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Kalenderansichten Monat, Woche und Agenda"
```

---

### Task 6: Termine anlegen, bearbeiten, löschen; einzelne Vorkommen ändern

**Files:**
- Create: `web/EventForm.java`, `templates/calendar/event-form.html`
- Modify: `web/CalendarController.java`
- Test: `src/test/java/club/badernet/nestwerk/web/CalendarEditTests.java`

**Interfaces:**
- Consumes: `CalendarEventService.create/update/delete/cancelOccurrence/overrideOccurrence/get/canEdit/canDelete`, `EventInput`, `OccurrenceInput`.
- Produces: Routen `GET /calendar/events/new?date=`, `POST /calendar/events`, `GET /calendar/events/{id}?occurrence=`, `POST /calendar/events/{id}`, `POST /calendar/events/{id}/delete`, `POST /calendar/events/{id}/occurrence/cancel` (`recurrenceId`), `POST /calendar/events/{id}/occurrence` (Override-Felder + `recurrenceId`).

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarEditTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired CalendarEventRepository repository;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Editor", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        userService.create("editor", "passwort1", Set.of(normal.getId()));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor editor() {
        return user("editor").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    @Test
    void createsEventFromForm() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                        .param("title", "Elternabend")
                        .param("startDate", "2026-10-20").param("startTime", "19:00")
                        .param("endDate", "2026-10-20").param("endTime", "21:00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        List<CalendarEvent> all = repository.findAll();
        assertThat(all).extracting(CalendarEvent::getTitle).contains("Elternabend");
    }

    @Test
    void invalidFormRerendersWithErrorAndKeepsInput() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                        .param("title", "Kaputt")
                        .param("startDate", "2026-10-20").param("startTime", "19:00")
                        .param("endDate", "2026-10-20").param("endTime", "18:00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Das Ende muss nach dem Beginn liegen.")))
                .andExpect(content().string(containsString("Kaputt")));
    }

    @Test
    void newFormIsShown() throws Exception {
        mockMvc.perform(get("/calendar/events/new").param("date", "2026-10-20").with(editor()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026-10-20")));
    }

    @Test
    void unknownEventIsNotFound() throws Exception {
        mockMvc.perform(get("/calendar/events/{id}", 999_999).with(editor()))
                .andExpect(status().isNotFound());
    }

    @Test
    void seriesOccurrenceCanBeCancelled() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                .param("title", "Training").param("rrule", "FREQ=WEEKLY")
                .param("startDate", "2026-10-05").param("startTime", "18:00")
                .param("endDate", "2026-10-05").param("endTime", "19:00"));
        CalendarEvent e = repository.findAll().stream()
                .filter(x -> x.getTitle().equals("Training")).findFirst().orElseThrow();

        mockMvc.perform(post("/calendar/events/{id}/occurrence/cancel", e.getId()).with(csrf()).with(editor())
                        .param("recurrenceId", "2026-10-12T16:00:00Z"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(repository.findById(e.getId()).orElseThrow().getExceptions()).hasSize(1);
    }

    @Test
    void deleteRemovesEvent() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                .param("title", "Weg damit")
                .param("startDate", "2026-10-21").param("startTime", "10:00")
                .param("endDate", "2026-10-21").param("endTime", "11:00"));
        CalendarEvent e = repository.findAll().stream()
                .filter(x -> x.getTitle().equals("Weg damit")).findFirst().orElseThrow();

        mockMvc.perform(post("/calendar/events/{id}/delete", e.getId()).with(csrf()).with(editor()))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findById(e.getId())).isEmpty();
    }
}
```
(Die Zeitzone Berlin im Oktober ist UTC+2: 18:00 lokal = 16:00Z, daher `recurrenceId=2026-10-12T16:00:00Z`.)

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarEditTests`
Expected: FAIL (404/405: Routen fehlen).

- [ ] **Step 3: Implementieren**

`web/EventForm.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.EventInput;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

public class EventForm {

    private String title;
    private String description;
    private String location;
    private boolean allDay;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime endTime;
    private String rrule;
    private boolean privateEvent;
    private Set<Long> memberIds = new HashSet<>();

    public static EventForm forNew(LocalDate date) {
        EventForm f = new EventForm();
        f.startDate = date;
        f.endDate = date;
        f.startTime = LocalTime.of(10, 0);
        f.endTime = LocalTime.of(11, 0);
        return f;
    }

    public static EventForm from(CalendarEvent e) {
        ZoneId zone = ZoneId.of(e.getTimezone());
        EventForm f = new EventForm();
        f.title = e.getTitle();
        f.description = e.getDescription();
        f.location = e.getLocation();
        f.allDay = e.isAllDay();
        f.startDate = e.getStartsAt().atZone(zone).toLocalDate();
        f.startTime = e.getStartsAt().atZone(zone).toLocalTime();
        if (e.isAllDay()) {
            f.endDate = e.getEndsAt().atZone(zone).toLocalDate().minusDays(1);
            f.endTime = null;
            f.startTime = null;
        } else {
            f.endDate = e.getEndsAt().atZone(zone).toLocalDate();
            f.endTime = e.getEndsAt().atZone(zone).toLocalTime();
        }
        f.rrule = e.getRrule();
        f.privateEvent = e.isPrivateEvent();
        f.memberIds = new HashSet<>(e.getMemberIds());
        return f;
    }

    public EventInput toInput() {
        return new EventInput(title, description, location, startDate, startTime, endDate, endTime,
                allDay, rrule, privateEvent, memberIds);
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public boolean isAllDay() { return allDay; }
    public void setAllDay(boolean allDay) { this.allDay = allDay; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getRrule() { return rrule; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public boolean isPrivateEvent() { return privateEvent; }
    public void setPrivateEvent(boolean privateEvent) { this.privateEvent = privateEvent; }
    public Set<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(Set<Long> memberIds) { this.memberIds = memberIds == null ? new HashSet<>() : memberIds; }
}
```

`CalendarController` ergänzen (zusätzliche Imports: `CalendarEvent`, `EventForm`, `OccurrenceInput`, `Occurrence`, `PathVariable`, `PostMapping`, `ModelAttribute`, `RedirectAttributes`, `LocalDateTime`, `AccessDeniedException` entfällt – Spring Security übersetzt zu 403):
```java
    @GetMapping("/events/new")
    String newForm(@RequestParam(required = false) String date, Model model) {
        model.addAttribute("form", EventForm.forNew(parseDate(date)));
        model.addAttribute("action", "/calendar/events");
        model.addAttribute("isNew", true);
        addFormData(model);
        return "calendar/event-form";
    }

    @PostMapping("/events")
    String create(@ModelAttribute("form") EventForm form, Model model, RedirectAttributes redirect) {
        try {
            CalendarEvent created = events.create(form.toInput(), currentUser.require());
            redirect.addFlashAttribute("success", "Termin '" + created.getTitle() + "' wurde angelegt.");
            return "redirect:/calendar/month?date=" + form.getStartDate();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("action", "/calendar/events");
            model.addAttribute("isNew", true);
            addFormData(model);
            return "calendar/event-form";
        }
    }

    @GetMapping("/events/{id}")
    String edit(@PathVariable Long id, @RequestParam(required = false) Instant occurrence, Model model) {
        User viewer = currentUser.require();
        CalendarEvent event = events.get(id, viewer);
        model.addAttribute("form", EventForm.from(event));
        model.addAttribute("event", event);
        model.addAttribute("action", "/calendar/events/" + id);
        model.addAttribute("isNew", false);
        model.addAttribute("canEdit", events.canEdit(event, viewer));
        model.addAttribute("canDelete", events.canDelete(event, viewer));
        model.addAttribute("recurring", event.getRrule() != null);
        if (occurrence != null && event.getRrule() != null) {
            model.addAttribute("occurrenceId", occurrence);
            events.findOccurrences(occurrence.minusSeconds(1), occurrence.plusSeconds(86_400L * 31), viewer)
                    .stream().filter(o -> o.eventId().equals(id) && o.recurrenceId().equals(occurrence))
                    .findFirst().ifPresent(o -> model.addAttribute("occurrence", o));
        }
        addFormData(model);
        return "calendar/event-form";
    }

    @PostMapping("/events/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") EventForm form,
                  Model model, RedirectAttributes redirect) {
        User actor = currentUser.require();
        try {
            events.update(id, form.toInput(), actor);
            redirect.addFlashAttribute("success", "Termin wurde gespeichert.");
            return "redirect:/calendar/month?date=" + form.getStartDate();
        } catch (IllegalArgumentException e) {
            CalendarEvent event = events.get(id, actor);
            model.addAttribute("error", e.getMessage());
            model.addAttribute("event", event);
            model.addAttribute("action", "/calendar/events/" + id);
            model.addAttribute("isNew", false);
            model.addAttribute("canEdit", events.canEdit(event, actor));
            model.addAttribute("canDelete", events.canDelete(event, actor));
            model.addAttribute("recurring", event.getRrule() != null);
            addFormData(model);
            return "calendar/event-form";
        }
    }

    @PostMapping("/events/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        events.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Termin wurde gelöscht.");
        return "redirect:/calendar/month";
    }

    @PostMapping("/events/{id}/occurrence/cancel")
    String cancelOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId,
                            RedirectAttributes redirect) {
        try {
            events.cancelOccurrence(id, recurrenceId, currentUser.require());
            redirect.addFlashAttribute("success", "Das Vorkommen wurde abgesagt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/month";
    }

    @PostMapping("/events/{id}/occurrence")
    String overrideOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId,
                              @RequestParam String title,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String location,
                              @RequestParam java.time.LocalDateTime start,
                              @RequestParam java.time.LocalDateTime end,
                              RedirectAttributes redirect) {
        try {
            events.overrideOccurrence(id, recurrenceId,
                    new club.badernet.nestwerk.calendar.OccurrenceInput(title, description, location, start, end),
                    currentUser.require());
            redirect.addFlashAttribute("success", "Das Vorkommen wurde geändert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/month";
    }

    private void addFormData(Model model) {
        model.addAttribute("allMembers", members.findAll());
    }
```
Wichtig für `@RequestParam java.time.LocalDateTime start/end`: im Formular `<input type="datetime-local">` liefert `yyyy-MM-ddTHH:mm`; dafür den Parametern `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)` voranstellen.

`templates/calendar/event-form.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('Termin')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1 th:text="${isNew} ? 'Neuer Termin' : 'Termin bearbeiten'"></h1>
<p th:if="${error}" class="flash-error" th:text="${error}"></p>

<form th:action="@{${action}}" method="post" th:object="${form}">
    <p><label>Titel <input type="text" th:field="*{title}" maxlength="200" required></label></p>
    <p><label><input type="checkbox" th:field="*{allDay}"> Ganztägig</label></p>
    <p>
        <label>Beginn <input type="date" th:field="*{startDate}" required></label>
        <label>Uhrzeit <input type="time" th:field="*{startTime}"></label>
    </p>
    <p>
        <label>Ende <input type="date" th:field="*{endDate}" required></label>
        <label>Uhrzeit <input type="time" th:field="*{endTime}"></label>
    </p>
    <p><label>Ort <input type="text" th:field="*{location}" maxlength="255"></label></p>
    <p><label>Beschreibung <textarea th:field="*{description}"></textarea></label></p>
    <p>
        <label>Wiederholung (iCal-RRULE)
            <input type="text" th:field="*{rrule}" list="rrule-presets" maxlength="500"
                   placeholder="leer = einmalig">
        </label>
        <datalist id="rrule-presets">
            <option value="FREQ=DAILY">täglich</option>
            <option value="FREQ=WEEKLY">wöchentlich</option>
            <option value="FREQ=WEEKLY;INTERVAL=2">alle 2 Wochen</option>
            <option value="FREQ=MONTHLY">monatlich</option>
            <option value="FREQ=MONTHLY;BYDAY=-1FR">jeden letzten Freitag im Monat</option>
            <option value="FREQ=YEARLY">jährlich</option>
        </datalist>
    </p>
    <fieldset>
        <legend>Betrifft</legend>
        <label th:each="m : ${allMembers}">
            <input type="checkbox" name="memberIds" th:value="${m.id}" th:checked="*{memberIds.contains(__${m.id}__L)}">
            <span class="dot" th:style="'background:' + ${m.color}"></span>
            <span th:text="${m.name}"></span>
        </label>
    </fieldset>
    <p sec:authorize="hasAuthority('CALENDAR_PRIVATE_CREATE')">
        <label><input type="checkbox" th:field="*{privateEvent}"> Privat (nur für mich und Administratoren sichtbar)</label>
    </p>
    <button type="submit" th:if="${isNew or canEdit}">Speichern</button>
</form>

<form th:if="${!isNew and canDelete}" th:action="@{/calendar/events/{id}/delete(id=${event.id})}" method="post">
    <button type="submit">Ganzen Termin löschen</button>
</form>

<section th:if="${occurrenceId != null}">
    <h2>Nur dieses Vorkommen</h2>
    <form th:if="${occurrence != null and canEdit}" th:action="@{/calendar/events/{id}/occurrence(id=${event.id})}" method="post">
        <input type="hidden" name="recurrenceId" th:value="${occurrenceId}">
        <p><label>Titel <input type="text" name="title" th:value="${occurrence.title}" required></label></p>
        <p><label>Beginn <input type="datetime-local" name="start"
              th:value="${#temporals.format(occurrence.start, 'yyyy-MM-dd''T''HH:mm')}" required></label></p>
        <p><label>Ende <input type="datetime-local" name="end"
              th:value="${#temporals.format(occurrence.end, 'yyyy-MM-dd''T''HH:mm')}" required></label></p>
        <p><label>Ort <input type="text" name="location" th:value="${occurrence.location}"></label></p>
        <button type="submit">Dieses Vorkommen speichern</button>
    </form>
    <form th:if="${canEdit}" th:action="@{/calendar/events/{id}/occurrence/cancel(id=${event.id})}" method="post">
        <input type="hidden" name="recurrenceId" th:value="${occurrenceId}">
        <button type="submit">Dieses Vorkommen absagen</button>
    </form>
</section>
</main>
</body>
</html>
```
Hinweis zu `th:checked="*{memberIds.contains(...)}"`: bei Problemen mit dem Long-Literal stattdessen `th:checked="${form.memberIds.contains(m.id)}"` verwenden.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=CalendarEditTests+CalendarViewTests`
Expected: PASS.

- [ ] **Step 5: Gesamtlauf und manuelle Prüfung**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

Manuell (`NESTWERK_ADMIN_PASSWORD=changeme ./mvnw spring-boot:run`): als `admin` anmelden → Menü „Kalender“ → „+ Termin“ mit Wiederholung `FREQ=WEEKLY` anlegen → in Monat/Woche/Agenda erscheinen die Vorkommen → ein Vorkommen öffnen und „absagen“ → es fehlt, die übrigen bleiben → privaten Termin anlegen, mit einem zweiten Benutzer anmelden: Termin ist nirgends sichtbar, `/calendar/events/{id}` liefert 404.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Termine anlegen, bearbeiten, löschen und einzelne Vorkommen ändern"
```
