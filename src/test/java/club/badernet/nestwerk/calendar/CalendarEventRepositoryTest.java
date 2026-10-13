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
    @Autowired club.badernet.nestwerk.user.UserRepository users;

    /** Legt einen Benutzer an; created_by verweist per Fremdschlüssel auf users. */
    private Long user(String name) {
        return users.save(new club.badernet.nestwerk.user.User(name, "x")).getId();
    }

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
                false, "Europe/Berlin", user("kal-mem"));
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
        Long u1 = user("kal-u1");
        Long u2 = user("kal-u2");
        Long u3 = user("kal-u3");
        event("oeffentlich", false, u1);
        event("privat-1", true, u1);
        event("privat-2", true, u2);
        Instant from = T0.minusSeconds(3600);
        Instant to = T0.plusSeconds(7200);

        List<String> forUser1 = events.findCandidates(from, to, u1, false).stream()
                .map(CalendarEvent::getUid).toList();
        List<String> forUser3 = events.findCandidates(from, to, u3, false).stream()
                .map(CalendarEvent::getUid).toList();
        List<String> forAdmin = events.findCandidates(from, to, u3, true).stream()
                .map(CalendarEvent::getUid).toList();

        assertThat(forUser1).contains("oeffentlich", "privat-1").doesNotContain("privat-2");
        assertThat(forUser3).contains("oeffentlich").doesNotContain("privat-1", "privat-2");
        assertThat(forAdmin).contains("oeffentlich", "privat-1", "privat-2");
    }

    @Test
    void candidatesIncludeRecurringEventsStartedBeforeWindowButNotLaterOnes() {
        Long u1 = user("kal-serie");
        CalendarEvent serie = event("serie", false, u1);
        serie.setRrule("FREQ=DAILY");
        events.saveAndFlush(serie);
        event("einzel-spaeter", false, u1);

        Instant from = T0.plusSeconds(10 * 24 * 3600);
        Instant to = from.plusSeconds(24 * 3600);

        assertThat(events.findCandidates(from, to, u1, false)).extracting(CalendarEvent::getUid)
                .contains("serie").doesNotContain("einzel-spaeter");
    }
}
