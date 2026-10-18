package club.badernet.nestwerk.kitchen.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bring! über die inoffizielle Web-API (wie die Web-App). Das Token liegt nur im Speicher; läuft es ab oder wird es
 * abgelehnt, meldet sich der Client einmal neu an.
 */
@Component
public class BringClient implements BringApi {

    private static final String API_KEY = "cof4Nc6D8saplXjE3h3HXqHH8m7VU2i1Gs0g85Sp";
    private static final String REJECTED = "Bring! lehnt die Anmeldung ab – bitte E-Mail und Passwort prüfen.";

    private record Session(String uuid, String token) {
    }

    private interface Call<T> {
        T run(Session session);
    }

    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public BringClient(@Value("${nestwerk.kitchen.bring-api:https://api.getbring.com/rest/v2}") String api) {
        this(RestClient.builder().requestFactory(factory()), api);
    }

    BringClient(RestClient.Builder builder, String api) {
        this.client = builder.baseUrl(api)
                .defaultHeader("X-BRING-API-KEY", API_KEY)
                .defaultHeader("X-BRING-CLIENT", "webApp")
                .defaultHeader("X-BRING-CLIENT-SOURCE", "webApp")
                .defaultHeader("X-BRING-COUNTRY", "DE")
                .build();
    }

    private static SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Override
    public List<BringList> lists(String email, String password) {
        return withSession(email, password, session -> {
            JsonNode root = read(client.get().uri("/bringusers/{uuid}/lists", session.uuid())
                    .header("Authorization", "Bearer " + session.token()).retrieve().body(String.class));
            List<BringList> result = new ArrayList<>();
            for (JsonNode l : root.path("lists")) {
                if (l.path("listUuid").isString()) {
                    result.add(new BringList(l.path("listUuid").asString(), l.path("name").asString("Bring!")));
                }
            }
            return result;
        });
    }

    @Override
    public List<String> open(String email, String password, String listUuid) {
        return withSession(email, password, session -> {
            JsonNode root = read(client.get().uri("/bringlists/{list}", listUuid)
                    .header("Authorization", "Bearer " + session.token()).retrieve().body(String.class));
            JsonNode purchase = root.path("items").isObject() ? root.path("items").path("purchase")
                    : root.path("purchase");
            List<String> names = new ArrayList<>();
            for (JsonNode item : purchase) {
                if (item.path("name").isString()) {
                    names.add(item.path("name").asString());
                }
            }
            return names;
        });
    }

    @Override
    public void add(String email, String password, String listUuid, String name, String specification) {
        withSession(email, password, session -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("purchase", name);
            form.add("recently", "");
            form.add("specification", specification == null ? "" : specification);
            form.add("remove", "");
            form.add("sender", "null");
            client.put().uri("/bringlists/{list}", listUuid)
                    .header("Authorization", "Bearer " + session.token())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
            return null;
        });
    }

    private <T> T withSession(String email, String password, Call<T> call) {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new BringException("Bring! ist nicht eingerichtet.");
        }
        try {
            Session session = sessions.get(email);
            if (session != null) {
                try {
                    return call.run(session);
                } catch (HttpClientErrorException.Unauthorized e) {
                    sessions.remove(email);
                }
            }
            session = login(email, password);
            sessions.put(email, session);
            return call.run(session);
        } catch (HttpClientErrorException.Unauthorized e) {
            sessions.remove(email);
            throw new BringException(REJECTED, e);
        } catch (BringException e) {
            throw e;
        } catch (RestClientException e) {
            throw new BringException("Bring! ist gerade nicht erreichbar.", e);
        }
    }

    private Session login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("email", email);
        form.add("password", password);
        String body;
        try {
            body = client.post().uri("/bringauth").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(String.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 400) {
                throw new BringException(REJECTED, e);
            }
            throw e;
        }
        JsonNode root = read(body);
        String uuid = root.path("uuid").asString("");
        String token = root.path("access_token").asString("");
        if (uuid.isEmpty() || token.isEmpty()) {
            throw new BringException("Bring! antwortet unerwartet.");
        }
        return new Session(uuid, token);
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JacksonException e) {
            throw new BringException("Bring! antwortet unerwartet.", e);
        }
    }
}
