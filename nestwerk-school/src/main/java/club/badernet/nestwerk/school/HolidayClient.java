package club.badernet.nestwerk.school;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Ruft Schulferien je Bundesland bei der OpenHolidays API ab. */
@Component
public class HolidayClient {

    /** Ein Ferienabschnitt der Quelle. */
    public record Entry(String externalId, String name, LocalDate startsOn, LocalDate endsOn) {
    }

    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public HolidayClient(@Value("${nestwerk.school.holidays-api:https://openholidaysapi.org}") String api) {
        this(RestClient.builder().requestFactory(factory()), api);
    }

    HolidayClient(RestClient.Builder builder, String api) {
        this.client = builder.baseUrl(api)
                .defaultHeader("User-Agent", "Nestwerk/1.0 (Familien-App; https://github.com/chrisbad/nestwerk)")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private static SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    /** Ferien des Bundeslands (Kürzel wie {@code BY}) im Zeitraum, einschließlich der Grenzen. */
    public List<Entry> fetch(String state, LocalDate from, LocalDate to) {
        String body;
        try {
            body = client.get().uri(b -> b.path("/SchoolHolidays")
                    .queryParam("countryIsoCode", "DE")
                    .queryParam("subdivisionCode", "DE-" + state)
                    .queryParam("languageIsoCode", "DE")
                    .queryParam("validFrom", from)
                    .queryParam("validTo", to)
                    .build()).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new HolidayException("Die Schulferien konnten nicht abgerufen werden.", e);
        }
        try {
            JsonNode root = json.readTree(body == null ? "" : body);
            if (!root.isArray()) {
                throw new HolidayException("Unerwartete Antwort der Ferienquelle.", null);
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                LocalDate start = date(node.path("startDate").asString(""));
                LocalDate end = date(node.path("endDate").asString(""));
                if (start == null || end == null || end.isBefore(start)) {
                    continue;
                }
                entries.add(new Entry(node.path("id").asString(null), name(node.path("name")), start, end));
            }
            return entries;
        } catch (JacksonException e) {
            throw new HolidayException("Unerwartete Antwort der Ferienquelle.", e);
        }
    }

    private static String name(JsonNode names) {
        String fallback = null;
        for (JsonNode n : names) {
            String text = n.path("text").asString("");
            if (text.isBlank()) {
                continue;
            }
            if ("DE".equalsIgnoreCase(n.path("language").asString(""))) {
                return clip(text);
            }
            if (fallback == null) {
                fallback = text;
            }
        }
        return clip(fallback == null ? "Ferien" : fallback);
    }

    private static String clip(String text) {
        String clean = text.trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
