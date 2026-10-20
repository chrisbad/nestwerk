package club.badernet.nestwerk.school;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HolidayClientTest {

    static final String API = "https://holidays.test";
    MockRestServiceServer server;
    HolidayClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HolidayClient(builder, API);
    }

    @Test
    void readsHolidaysWithGermanNames() {
        server.expect(requestTo(containsString(API + "/SchoolHolidays")))
                .andExpect(requestTo(containsString("subdivisionCode=DE-BY")))
                .andExpect(requestTo(containsString("validFrom=2026-09-01")))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.startsWith("Nestwerk")))
                .andRespond(withSuccess("""
                        [{"id":"abc-1","startDate":"2026-10-30","endDate":"2026-11-06","type":"School",
                          "name":[{"language":"EN","text":"Autumn holidays"},{"language":"DE","text":"Herbstferien"}],
                          "nationwide":false},
                         {"id":"abc-2","startDate":"2026-12-23","endDate":"2027-01-05","type":"School",
                          "name":[{"language":"EN","text":"Christmas holidays"}]}]
                        """, MediaType.APPLICATION_JSON));

        List<HolidayClient.Entry> entries = client.fetch("BY", LocalDate.of(2026, 9, 1), LocalDate.of(2028, 9, 1));

        assertThat(entries).extracting(HolidayClient.Entry::name).containsExactly("Herbstferien", "Christmas holidays");
        assertThat(entries.getFirst().startsOn()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(entries.getFirst().endsOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(entries.getFirst().externalId()).isEqualTo("abc-1");
        server.verify();
    }

    @Test
    void skipsBrokenEntries() {
        server.expect(requestTo(containsString("/SchoolHolidays")))
                .andRespond(withSuccess("""
                        [{"id":"1","startDate":"kaputt","endDate":"2026-11-06","name":[{"language":"DE","text":"X"}]},
                         {"id":"2","startDate":"2026-11-10","endDate":"2026-11-01","name":[{"language":"DE","text":"Y"}]},
                         {"id":"3","startDate":"2026-11-01","endDate":"2026-11-02","name":[]}]
                        """, MediaType.APPLICATION_JSON));

        List<HolidayClient.Entry> entries = client.fetch("BY", LocalDate.of(2026, 9, 1), LocalDate.of(2028, 9, 1));

        assertThat(entries).extracting(HolidayClient.Entry::name).containsExactly("Ferien");
    }

    @Test
    void reportsUnreachableSourceAndGarbage() {
        server.expect(requestTo(containsString("/SchoolHolidays"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.fetch("BY", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1)))
                .isInstanceOf(HolidayException.class).hasMessageContaining("nicht abgerufen");

        server.reset();
        server.expect(requestTo(containsString("/SchoolHolidays")))
                .andRespond(withSuccess("{\"fehler\":true}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetch("BY", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1)))
                .isInstanceOf(HolidayException.class).hasMessageContaining("Unerwartete");
    }
}
