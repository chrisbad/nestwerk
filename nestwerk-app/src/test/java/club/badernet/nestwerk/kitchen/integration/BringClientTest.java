package club.badernet.nestwerk.kitchen.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BringClientTest {

    static final String API = "https://bring.test/rest/v2";
    static final String LOGIN = """
            {"uuid":"user-1","access_token":"tok-1","refresh_token":"ref-1","name":"Lena"}""";

    MockRestServiceServer server;
    BringClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BringClient(builder, API);
    }

    private void expectLogin() {
        server.expect(once(), requestTo(API + "/bringauth")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-BRING-API-KEY", containsString("cof4")))
                .andExpect(content().string(containsString("email=lena%40example.org")))
                .andRespond(withSuccess(LOGIN, MediaType.APPLICATION_JSON));
    }

    @Test
    void logsInAndReadsLists() {
        expectLogin();
        server.expect(once(), requestTo(API + "/bringusers/user-1/lists"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("""
                        {"lists":[{"listUuid":"l-1","name":"Zuhause","theme":"ch.publisheria.bring.theme.home"},
                                  {"listUuid":"l-2","name":"Garten"}]}""", MediaType.APPLICATION_JSON));

        List<BringList> lists = client.lists("lena@example.org", "geheim");

        assertThat(lists).containsExactly(new BringList("l-1", "Zuhause"), new BringList("l-2", "Garten"));
        server.verify();
    }

    @Test
    void readsOpenItemsAndReusesTheToken() {
        expectLogin();
        server.expect(once(), requestTo(API + "/bringlists/l-1")).andRespond(withSuccess("""
                {"uuid":"l-1","items":{"purchase":[{"name":"Mehl","specification":"1 kg"},{"name":"Milch"}],
                 "recently":[{"name":"Brot"}]}}""", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "/bringlists/l-1")).andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("{\"items\":{\"purchase\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.open("lena@example.org", "geheim", "l-1")).containsExactly("Mehl", "Milch");
        assertThat(client.open("lena@example.org", "geheim", "l-1")).isEmpty();
        server.verify();
    }

    @Test
    void addsAnItemWithSpecification() {
        expectLogin();
        server.expect(once(), requestTo(API + "/bringlists/l-1")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString("purchase=Mehl")))
                .andExpect(content().string(containsString("specification=500+g")))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.add("lena@example.org", "geheim", "l-1", "Mehl", "500 g");

        server.verify();
    }

    @Test
    void wrongPasswordGivesAClearMessage() {
        server.expect(once(), requestTo(API + "/bringauth")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.lists("lena@example.org", "falsch"))
                .isInstanceOf(BringException.class).hasMessageContaining("Anmeldung ab");
    }

    @Test
    void unreachableBringGivesAMessageNotAStackTrace() {
        expectLogin();
        server.expect(once(), requestTo(API + "/bringusers/user-1/lists"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.lists("lena@example.org", "geheim"))
                .isInstanceOf(BringException.class).hasMessageContaining("nicht erreichbar");
    }

    @Test
    void expiredTokenTriggersOneNewLogin() {
        expectLogin();
        server.expect(once(), requestTo(API + "/bringlists/l-1"))
                .andRespond(withSuccess("{\"items\":{\"purchase\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "/bringlists/l-1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin();
        server.expect(once(), requestTo(API + "/bringlists/l-1"))
                .andRespond(withSuccess("{\"items\":{\"purchase\":[{\"name\":\"Eier\"}]}}", MediaType.APPLICATION_JSON));

        client.open("lena@example.org", "geheim", "l-1");

        assertThat(client.open("lena@example.org", "geheim", "l-1")).containsExactly("Eier");
        server.verify();
    }

    @Test
    void missingCredentialsAreReportedWithoutACall() {
        assertThatThrownBy(() -> client.lists("", "x")).isInstanceOf(BringException.class)
                .hasMessageContaining("nicht eingerichtet");
        server.verify();
    }
}
