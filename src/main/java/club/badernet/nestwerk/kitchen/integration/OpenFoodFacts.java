package club.badernet.nestwerk.kitchen.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Produktsuche per Barcode bei Open Food Facts. */
@Component
public class OpenFoodFacts {

    private static final String FIELDS = "product_name,product_name_de,brands,product_quantity,"
            + "product_quantity_unit,quantity,nutriments";

    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public OpenFoodFacts(@Value("${nestwerk.kitchen.off-api:https://world.openfoodfacts.org}") String api) {
        this(RestClient.builder().requestFactory(factory()), api);
    }

    OpenFoodFacts(RestClient.Builder builder, String api) {
        this.client = builder.baseUrl(api)
                .defaultHeader("User-Agent", "Nestwerk/1.0 (Familien-App; https://github.com/chrisbad/nestwerk)")
                .build();
    }

    private static SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    public Optional<OffProduct> product(String barcode) {
        if (barcode == null || !barcode.matches("\\d{6,14}")) {
            return Optional.empty();
        }
        String body;
        try {
            body = client.get().uri("/api/v2/product/{code}.json?fields=" + FIELDS, barcode).retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ExternalServiceException("Open Food Facts ist gerade nicht erreichbar.", e);
        }
        JsonNode root;
        try {
            root = json.readTree(body == null ? "{}" : body);
        } catch (JacksonException e) {
            throw new ExternalServiceException("Open Food Facts antwortet unerwartet.", e);
        }
        JsonNode p = root.path("product");
        if (root.path("status").asInt() != 1 || !p.isObject()) {
            return Optional.empty();
        }
        String name = firstNonBlank(text(p, "product_name_de"), text(p, "product_name"));
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String brand = text(p, "brands").split(",")[0].strip();
        JsonNode n = p.path("nutriments");
        return Optional.of(new OffProduct(barcode, name.length() > 100 ? name.substring(0, 100) : name,
                brand.isEmpty() ? null : brand, number(n, "energy-kcal_100g"), number(n, "proteins_100g"),
                number(n, "fat_100g"), number(n, "carbohydrates_100g"), grams(p)));
    }

    private static Double grams(JsonNode p) {
        Double amount = number(p, "product_quantity");
        String unit = text(p, "product_quantity_unit").toLowerCase(Locale.ROOT);
        if (amount == null || amount <= 0) {
            return null;
        }
        return switch (unit) {
            case "kg", "l" -> amount * 1000;
            case "", "g", "ml" -> amount;
            default -> null;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString().strip() : "";
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isString()) {
            try {
                return Double.parseDouble(v.asString().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        return a.isEmpty() ? b : a;
    }
}
