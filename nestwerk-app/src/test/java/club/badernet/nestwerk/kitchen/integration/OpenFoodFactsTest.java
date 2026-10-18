package club.badernet.nestwerk.kitchen.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenFoodFactsTest {

    static final String API = "https://off.test";
    MockRestServiceServer server;
    OpenFoodFacts off;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        off = new OpenFoodFacts(builder, API);
    }

    @Test
    void readsProductWithGermanNameAndNutrients() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(API + "/api/v2/product/4000417025005.json")))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.startsWith("Nestwerk")))
                .andRespond(withSuccess("""
                        {"status":1,"product":{"product_name":"Chocolate","product_name_de":"Vollmilch-Schokolade",
                         "brands":"Ritter Sport,Alfred Ritter","product_quantity":"100","product_quantity_unit":"g",
                         "nutriments":{"energy-kcal_100g":537,"proteins_100g":6.3,"fat_100g":31,"carbohydrates_100g":56}}}
                        """, MediaType.APPLICATION_JSON));

        OffProduct p = off.product("4000417025005").orElseThrow();

        assertThat(p.name()).isEqualTo("Vollmilch-Schokolade");
        assertThat(p.brand()).isEqualTo("Ritter Sport");
        assertThat(p.kcal()).isEqualTo(537.0);
        assertThat(p.carbs()).isEqualTo(56.0);
        assertThat(p.packageGrams()).isEqualTo(100.0);
    }

    @Test
    void unknownProductIsEmpty() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(API + "/api/v2/product/123456.json")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"status\":0}").contentType(MediaType.APPLICATION_JSON));
        assertThat(off.product("123456")).isEmpty();
    }

    @Test
    void outageIsReportedClearly() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(API)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> off.product("123456")).isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("Open Food Facts");
    }
}
