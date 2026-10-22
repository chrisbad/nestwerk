package club.badernet.nestwerk.transfer;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Export und Import von Kalender, Kontakten und ToDos. Ohne Test-Transaktion, weil jeder Datensatz in einer
 * eigenen Transaktion läuft; am Ende wird der Ausgangsstand per Sicherung wiederhergestellt.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/backup-test/attachments",
        "nestwerk.backup.dir=target/backup-test/backups",
        "nestwerk.backup.external-dir=target/backup-test/external"})
class TransferRoundTripTest {

    @Autowired CalendarTransfer calendarTransfer;
    @Autowired ContactTransfer contactTransfer;
    @Autowired TodoTransfer todoTransfer;
    @Autowired CalendarEventService events;
    @Autowired club.badernet.nestwerk.calendar.EventCategoryService categories;
    @Autowired CalendarEventRepository eventRepository;
    @Autowired ContactService contacts;
    @Autowired ContactRepository contactRepository;
    @Autowired TodoService todos;
    @Autowired TodoRepository todoRepository;
    @Autowired FamilyMemberRepository members;
    @Autowired RoleService roleService;
    @Autowired AclService acl;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired BackupService backups;
    @Autowired JdbcTemplate jdbc;

    User admin;
    String baseline;

    @BeforeEach
    void setUp() {
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
        baseline = backups.backupNow(BackupRun.Kind.PRE_RESTORE, admin).getFileName();
    }

    @AfterEach
    void tearDown() {
        backups.restore(baseline, users.findByUsername("chef").orElseThrow());
    }

    // --- Kalender ----------------------------------------------------------------------

    @Test
    void calendarRoundTripKeepsSeriesAndUpdatesByUid() {
        CalendarEvent weekly = events.create(new EventInput("Chorprobe; mit Komma, und\nZeilenumbruch", "Noten mitbringen",
                "Gemeindehaus", LocalDate.of(2026, 10, 5), LocalTime.of(19, 30), LocalDate.of(2026, 10, 5),
                LocalTime.of(21, 0), false, "FREQ=WEEKLY;BYDAY=MO", false, Set.of()), admin);
        events.cancelOccurrence(weekly.getId(), LocalDate.of(2026, 10, 12).atTime(19, 30)
                .atZone(java.time.ZoneId.of("Europe/Berlin")).toInstant(), admin);
        events.create(new EventInput("Urlaub", null, null, LocalDate.of(2026, 12, 23), null,
                LocalDate.of(2026, 12, 27), null, true, null, false, Set.of()), admin);

        String ics = calendarTransfer.exportIcs(admin);
        assertThat(ics).contains("BEGIN:VTIMEZONE", "RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE;TZID=Europe/Berlin:20261012T193000",
                "DTSTART;VALUE=DATE:20261223", "DTEND;VALUE=DATE:20261228", "SUMMARY:Chorprobe\\; mit Komma\\, und\\nZeilenumbruch");
        assertThat(ics.lines().allMatch(l -> l.getBytes(StandardCharsets.UTF_8).length <= 75)).isTrue();

        // gleiche UIDs: alles wird aktualisiert, nichts doppelt angelegt
        ImportReport again = calendarTransfer.importIcs(ics.getBytes(StandardCharsets.UTF_8), admin, true);
        assertThat(again.getUpdateCount()).isEqualTo(2);
        assertThat(again.getNewCount()).isZero();

        // in einen leeren Kalender importieren
        jdbc.update("DELETE FROM calendar_events");
        ImportReport preview = calendarTransfer.importIcs(ics.getBytes(StandardCharsets.UTF_8), admin, false);
        assertThat(preview.getNewCount()).isEqualTo(2);
        assertThat(eventRepository.count()).isZero();

        ImportReport done = calendarTransfer.importIcs(ics.getBytes(StandardCharsets.UTF_8), admin, true);
        assertThat(done.getNewCount()).isEqualTo(2);
        CalendarEvent restored = eventRepository.findByUid(weekly.getUid()).orElseThrow();
        assertThat(restored.getTitle()).isEqualTo("Chorprobe; mit Komma, und\nZeilenumbruch");
        assertThat(restored.getRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
        assertThat(restored.getStartsAt()).isEqualTo(weekly.getStartsAt());
        assertThat(eventRepository.findAll()).filteredOn(CalendarEvent::isAllDay).singleElement()
                .satisfies(e -> assertThat(e.getEndsAt()).isEqualTo(LocalDate.of(2026, 12, 28)
                        .atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant()));
    }

    @Test
    void noOverlapFlagTravelsInTheIcsFile() {
        CalendarEvent flagged = events.create(new EventInput("Operation", null, null, LocalDate.of(2026, 11, 3),
                LocalTime.of(9, 0), LocalDate.of(2026, 11, 3), LocalTime.of(11, 0), false, null, false, Set.of(),
                false, null, true), admin);
        events.create(new EventInput("Kaffee", null, null, LocalDate.of(2026, 11, 3), LocalTime.of(15, 0),
                LocalDate.of(2026, 11, 3), LocalTime.of(16, 0), false, null, false, Set.of()), admin);

        String ics = calendarTransfer.exportIcs(admin);
        assertThat(ics.split("X-NESTWERK-NO-OVERLAP:TRUE", -1)).hasSize(2);

        jdbc.update("DELETE FROM calendar_events");
        calendarTransfer.importIcs(ics.getBytes(StandardCharsets.UTF_8), admin, true);

        assertThat(eventRepository.findByUid(flagged.getUid()).orElseThrow().isNoOverlap()).isTrue();
        assertThat(eventRepository.findAll()).filteredOn(e -> !e.isNoOverlap()).hasSize(1);
    }

    @Test
    void categoriesTravelByNameAndUnknownNamesAreIgnored() {
        Long doctor = categories.findByName("Arzt", admin).orElseThrow().getId();
        CalendarEvent checkup = events.create(new EventInput("Vorsorge", null, null, LocalDate.of(2026, 11, 2),
                LocalTime.of(8, 30), LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), false, null, false, Set.of(),
                false, doctor), admin);

        String ics = calendarTransfer.exportIcs(admin);
        assertThat(ics).contains("CATEGORIES:Arzt");

        jdbc.update("DELETE FROM calendar_events");
        String foreign = ics.replace("UID:" + checkup.getUid(), "UID:fremd-1@example")
                .replace("CATEGORIES:Arzt", "CATEGORIES:Unbekannt\\, sonst,Arzt");
        calendarTransfer.importIcs(ics.getBytes(StandardCharsets.UTF_8), admin, true);
        calendarTransfer.importIcs(foreign.getBytes(StandardCharsets.UTF_8), admin, true);

        assertThat(eventRepository.findByUid(checkup.getUid()).orElseThrow().getCategoryId()).contains(doctor);
        assertThat(eventRepository.findByUid("fremd-1@example").orElseThrow().getCategoryId()).isEmpty();
    }

    @Test
    void foreignIcsWithUtcAndDurationIsImported() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Google Inc//Google Calendar 70.9054//EN
                BEGIN:VEVENT
                UID:abc123@google.com
                DTSTART:20261020T080000Z
                DURATION:PT90M
                SUMMARY:Zahnarzt
                END:VEVENT
                BEGIN:VEVENT
                UID:abc123@google.com
                RECURRENCE-ID:20261020T080000Z
                DTSTART:20261020T090000Z
                DTEND:20261020T100000Z
                SUMMARY:Zahnarzt verschoben
                END:VEVENT
                BEGIN:VEVENT
                SUMMARY:Ohne Beginn
                END:VEVENT
                END:VCALENDAR
                """.replace("\n", "\r\n");

        ImportReport report = calendarTransfer.importIcs(ics.getBytes(StandardCharsets.UTF_8), admin, true);

        assertThat(report.getNewCount()).isEqualTo(1);
        assertThat(report.notes()).hasSize(2);
        CalendarEvent e = eventRepository.findByUid("abc123@google.com").orElseThrow();
        assertThat(e.getStartsAt()).isEqualTo(java.time.Instant.parse("2026-10-20T08:00:00Z"));
        assertThat(e.getEndsAt()).isEqualTo(java.time.Instant.parse("2026-10-20T09:30:00Z"));
    }

    // --- Kontakte ----------------------------------------------------------------------

    @Test
    void contactsRoundTripAsVcardAndCsvWithDuplicateDetection() {
        contacts.create(new ContactInput("Dr. Müller", "Ärzte", "Praxis am Markt", "+49 30 1234", "praxis@example.org",
                "Marktplatz 1", "10115", "Berlin", "https://praxis.example.org", "Mo–Fr 8–12"), admin);
        contacts.create(new ContactInput("Klempner Meier", "Handwerker", null, "0170 999", null, null, null, null,
                null, null), admin);

        String vcf = contactTransfer.exportVcf(admin);
        assertThat(vcf).contains("BEGIN:VCARD", "VERSION:4.0", "FN:Dr. Müller", "ORG:Praxis am Markt");
        byte[] csv = contactTransfer.exportCsv(admin);
        String csvText = new String(csv, StandardCharsets.UTF_8);
        assertThat(csvText).startsWith("﻿Name;Kategorie;Firma;Telefon").contains("Dr. Müller;Ärzte;Praxis am Markt");

        // Wiedereinlesen erkennt alles als vorhanden
        ImportReport again = contactTransfer.importVcf(vcf.getBytes(StandardCharsets.UTF_8), admin, true);
        assertThat(again.getUpdateCount()).isEqualTo(2);
        assertThat(contactRepository.count()).isEqualTo(2);

        jdbc.update("DELETE FROM contacts");
        ImportReport fromCsv = contactTransfer.importCsv(csv, admin, true);
        assertThat(fromCsv.getNewCount()).isEqualTo(2);
        Contact mueller = contactRepository.findAll().stream().filter(c -> c.getName().equals("Dr. Müller"))
                .findFirst().orElseThrow();
        assertThat(mueller.getCity()).isEqualTo("Berlin");
        assertThat(mueller.getNote()).isEqualTo("Mo–Fr 8–12");
    }

    @Test
    void googleCsvWithFirstAndLastNameIsUnderstood() {
        String csv = "First Name,Last Name,E-mail 1 - Value,Phone 1 - Value,Organization 1 - Name\n"
                + "Anna,Schmidt,anna@example.org,+49 171 1,Schule\n"
                + "Anna,Schmidt,anna@example.org,+49 171 1,Schule\n"
                + ",,,,\n";

        ImportReport report = contactTransfer.importCsv(csv.getBytes(StandardCharsets.UTF_8), admin, true);

        assertThat(report.getNewCount()).isEqualTo(1);
        assertThat(report.rows()).filteredOn(r -> r.action() == ImportReport.Action.SKIP)
                .extracting(ImportReport.Row::problem).contains("doppelt in der Datei");
        assertThat(contactRepository.findAll()).extracting(Contact::getName).contains("Anna Schmidt");
    }

    // --- ToDos -------------------------------------------------------------------------

    @Test
    void todosRoundTripWithNamesStatusAndUpdates() {
        FamilyMember mama = members.save(new FamilyMember("Mama", "#aa0000", null));
        Role eltern = roleService.create("Eltern", null, Set.of());
        Todo keller = todos.create(new TodoInput("Keller aufräumen", "Alles raus", TodoPriority.HIGH, null,
                LocalDate.of(2026, 11, 30), null, null, false, null, Set.of("Haus", "Keller"),
                Set.of(AssigneeRef.member(mama.getId()), AssigneeRef.role(eltern.getId())), null), admin);
        todos.changeStatus(keller.getId(), TodoStatus.IN_PROGRESS, admin);

        byte[] csv = todoTransfer.exportCsv(admin, TodoFilter.defaults());
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("Keller aufräumen", "In Arbeit", "Hoch", "30.11.2026")
                .containsIgnoringCase("Haus, Keller")
                .contains("Mama").contains("Rolle: Eltern");

        // mit ID: Aktualisierung
        String changed = text.replace("Keller aufräumen", "Keller ausmisten");
        ImportReport update = todoTransfer.importCsv(changed.getBytes(StandardCharsets.UTF_8), admin, true);
        assertThat(update.getUpdateCount()).isEqualTo(1);
        assertThat(todoRepository.findById(keller.getId()).orElseThrow().getTitle()).isEqualTo("Keller ausmisten");

        // ohne ID und mit unbekanntem Zuständigen
        String fresh = "Titel;Status;Bis;Zuständige;Priorität\n"
                + "Fenster putzen;Erledigt;1.12.2026;Mama;Dringend\n"
                + "Garage;Offen;;Oma Erna;\n"
                + "Dachboden;Offen;31.02.2026;;\n";
        ImportReport preview = todoTransfer.importCsv(fresh.getBytes(StandardCharsets.UTF_8), admin, false);
        assertThat(preview.getNewCount()).isEqualTo(1);
        assertThat(preview.rows()).extracting(ImportReport.Row::problem).anyMatch(p -> p != null
                && p.contains("Oma Erna")).anyMatch(p -> p != null && p.contains("31.02.2026"));
        assertThat(todoRepository.findAll()).extracting(Todo::getTitle).doesNotContain("Fenster putzen");

        todoTransfer.importCsv(fresh.getBytes(StandardCharsets.UTF_8), admin, true);
        Todo window = todoRepository.findAll().stream().filter(t -> t.getTitle().equals("Fenster putzen"))
                .findFirst().orElseThrow();
        assertThat(window.getStatus()).isEqualTo(TodoStatus.DONE);
        assertThat(window.getPriority()).isEqualTo(TodoPriority.URGENT);
        assertThat(window.assigneeRefs()).containsExactly(AssigneeRef.member(mama.getId()));
    }

    @Test
    void todoCsvDoesNotRevealInvisibleContacts() {
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        User kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        FamilyMember kidMember = members.save(new FamilyMember("Kind", "#0000aa", kid.getId()));
        Contact secret = contacts.create(new ContactInput("Geheimer Kontakt", null, null, null, null, null, null, null,
                null, null), admin);
        todos.create(new TodoInput("Hausaufgaben", null, null, null, null, null, null, false, null, Set.of(),
                Set.of(AssigneeRef.member(kidMember.getId()), AssigneeRef.contact(secret.getId())), null), admin);

        String export = new String(todoTransfer.exportCsv(kid, TodoFilter.defaults()), StandardCharsets.UTF_8);
        assertThat(export).contains("Hausaufgaben", "Kontakt: (nicht sichtbar)").doesNotContain("Geheimer Kontakt");

        String csv = "Titel;Zuständige\nTest;Kontakt: Geheimer Kontakt\n";
        ImportReport report = todoTransfer.importCsv(csv.getBytes(StandardCharsets.UTF_8), kid, false);
        assertThat(report.rows()).singleElement().extracting(ImportReport.Row::problem).asString()
                .contains("unbekannte Zuständige");
    }

    @Test
    void importNeedsRights() {
        User nobody = userService.create("niemand", "passwort1", Set.of());

        assertThatThrownBy(() -> calendarTransfer.importIcs(new byte[0], nobody, false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> contactTransfer.importCsv("Name\nA\n".getBytes(), nobody, false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> todoTransfer.importCsv("Titel\nA\n".getBytes(), nobody, false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
