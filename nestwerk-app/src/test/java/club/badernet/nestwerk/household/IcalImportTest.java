package club.badernet.nestwerk.household;

import club.badernet.nestwerk.net.IcalFetcher;
import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class IcalImportTest {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired IcalImporter importer;
    @Autowired ChoreService chores;
    @Autowired ChoreDateRepository dates;
    @Autowired ChoreTaskRepository tasks;
    @Autowired UserService userService;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    FamilyMember tim;
    Chore gelb;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        tim = members.save(new FamilyMember("Tim", "#0000aa", null));
        gelb = chores.create(new ChoreInput("Gelbe Tonne rausstellen", null, null, 5, 5, 5,
                ChoreKind.DATES, null, null, null, null, Distribution.FIXED, List.of(tim.getId())), admin);
        today = LocalDate.now(clock);
    }

    private static String event(String uid, String summary, LocalDate day) {
        return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:" + day.format(BASIC)
                + "\r\nSUMMARY:" + summary + "\r\nEND:VEVENT\r\n";
    }

    private static byte[] calendar(String... events) {
        return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Gemeinde//Abfuhr//DE\r\n" + String.join("", events)
                + "END:VCALENDAR\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private List<ChoreDate> datesOf() {
        entityManager.flush();
        return dates.findAllByChoreIdOrderByDueOnAscDueTimeAsc(gelb.getId());
    }

    @Test
    void importsFilteredFutureEventsOnTheEveBefore() {
        byte[] ics = calendar(
                event("a1", "Gelber Sack", today.plusDays(4)),
                event("a2", "Restmüll", today.plusDays(5)),
                event("a3", "Gelber Sack", today.minusDays(3)),
                event("a4", "GELBER Sack", today.plusDays(18)));

        IcalImporter.Result result = importer.importFile(gelb.getId(), ics, "gelb", 1, LocalTime.of(19, 0), admin);

        assertThat(result.total()).isEqualTo(2);
        assertThat(datesOf()).extracting(ChoreDate::getDueOn)
                .containsExactly(today.plusDays(3), today.plusDays(17));
        assertThat(datesOf()).allMatch(d -> d.getSource() == ChoreDateSource.ICAL && LocalTime.of(19, 0).equals(d.getDueTime()));
        assertThat(tasks.findAllByChoreId(gelb.getId())).extracting(ChoreTask::getSlotKey)
                .containsExactlyInAnyOrder(today.plusDays(3) + "T19:00", today.plusDays(17) + "T19:00");
    }

    @Test
    void reimportMovesChangedAndRemovesVanishedButKeepsManualDates() {
        chores.addDate(gelb.getId(), today.plusDays(10), null, admin);
        importer.importFile(gelb.getId(), calendar(event("a1", "Gelb", today.plusDays(4)),
                event("a2", "Gelb", today.plusDays(8))), null, 0, null, admin);

        IcalImporter.Result result = importer.importFile(gelb.getId(), calendar(event("a1", "Gelb", today.plusDays(5)),
                event("a3", "Gelb", today.plusDays(12))), null, 0, null, admin);

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(datesOf()).extracting(ChoreDate::getDueOn)
                .containsExactly(today.plusDays(5), today.plusDays(10), today.plusDays(12));
        assertThat(tasks.findAllByChoreId(gelb.getId())).extracting(ChoreTask::getDueOn)
                .containsExactlyInAnyOrder(today.plusDays(5), today.plusDays(10), today.plusDays(12));
    }

    @Test
    void expandsRecurringEvents() {
        String weekly = "BEGIN:VEVENT\r\nUID:serie\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:"
                + today.minusDays(7).format(BASIC) + "\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:Papier\r\nEND:VEVENT\r\n";

        importer.importFile(gelb.getId(), calendar(weekly), null, 0, null, admin);

        assertThat(datesOf()).extracting(ChoreDate::getDueOn)
                .containsExactly(today, today.plusDays(7), today.plusDays(14));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> importer.importFile(gelb.getId(), "kein kalender".getBytes(), null, 0, null, admin))
                .hasMessageContaining("kein gültiger iCal");
        assertThatThrownBy(() -> importer.importFile(gelb.getId(), new byte[0], null, 0, null, admin))
                .hasMessageContaining("Datei");
        assertThatThrownBy(() -> importer.importFile(gelb.getId(), calendar(), null, 9, null, admin))
                .hasMessageContaining("Tage vorher");
        Chore recurring = chores.create(new ChoreInput("Bad", null, null, 5, 5, 5, ChoreKind.INTERVAL,
                null, today, null, 7, Distribution.OPEN, List.of()), admin);
        assertThatThrownBy(() -> importer.importFile(recurring.getId(), calendar(), null, 0, null, admin))
                .hasMessageContaining("festen Terminen");
    }

    @Test
    void urlGuardRejectsInternalTargetsAndOddPorts() {
        IcalFetcher strict = new IcalFetcher(false);

        assertThatThrownBy(() -> strict.validate("http://127.0.0.1/abfuhr.ics")).hasMessageContaining("Interne");
        assertThatThrownBy(() -> strict.validate("http://localhost/abfuhr.ics")).hasMessageContaining("Interne");
        assertThatThrownBy(() -> strict.validate("http://192.168.1.10/abfuhr.ics")).hasMessageContaining("Interne");
        assertThatThrownBy(() -> strict.validate("http://10.0.0.1/abfuhr.ics")).hasMessageContaining("Interne");
        assertThatThrownBy(() -> strict.validate("http://[::1]/abfuhr.ics")).hasMessageContaining("Interne");
        assertThatThrownBy(() -> strict.validate("http://169.254.169.254/latest")).hasMessageContaining("Interne");
        assertThatThrownBy(() -> strict.validate("https://example.org:8443/a.ics")).hasMessageContaining("Port");
        assertThatThrownBy(() -> strict.validate("file:///etc/passwd")).hasMessageContaining("https://");
        assertThatThrownBy(() -> strict.validate("https://user:pw@example.org/a.ics")).hasMessageContaining("ungültig");
        assertThat(IcalFetcher.isInternal(InetAddress.getLoopbackAddress())).isTrue();
    }

    @Test
    void fetchLoadsCalendarButRefusesRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = calendar(event("x", "Gelb", today.plusDays(1)));
        server.createContext("/ok.ics", ex -> {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/weiter.ics", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            IcalFetcher lenient = new IcalFetcher(true);

            assertThat(lenient.fetch(base + "/ok.ics")).isEqualTo(body);
            assertThatThrownBy(() -> lenient.fetch(base + "/weiter.ics")).hasMessageContaining("leitet weiter");
            assertThatThrownBy(() -> new IcalFetcher(false).fetch(base + "/ok.ics")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            server.stop(0);
        }
    }
}
