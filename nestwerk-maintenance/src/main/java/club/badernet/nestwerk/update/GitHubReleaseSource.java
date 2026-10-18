package club.badernet.nestwerk.update;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Releases und main-Stände von GitHub. Für den main-Kanal zählt der letzte erfolgreiche Lauf von
 * {@code release.yml}, denn erst dann gibt es das Image {@code sha-<sha7>}.
 */
@Component
public class GitHubReleaseSource implements ReleaseSource {

    private final RestClient client;
    private final Supplier<Optional<String>> token;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public GitHubReleaseSource(UpdateSettings settings,
                               @Value("${nestwerk.update.api:https://api.github.com/repos/chrisbad/nestwerk}")
                               String api) {
        this(RestClient.builder().requestFactory(factory()), api, settings::token);
    }

    GitHubReleaseSource(RestClient.Builder builder, String api, Supplier<Optional<String>> token) {
        this.client = builder.baseUrl(api).defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
        this.token = token;
    }

    private static SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    @Override
    public Optional<AvailableUpdate> latest(Channel channel, AppVersion current) {
        return channel == Channel.MAIN ? latestMain(current) : latestRelease(current);
    }

    private Optional<AvailableUpdate> latestRelease(AppVersion current) {
        JsonNode release;
        try {
            release = getJson("/releases/latest");
        } catch (HttpClientErrorException.NotFound e) {
            if (token.get().isEmpty()) {
                throw new UpdateSourceException(privateRepoMessage(), e);
            }
            if (!canReadContents()) {
                throw new UpdateSourceException(tokenRightsMessage("Contents"), e);
            }
            return Optional.empty();
        }
        String tag = text(release, "tag_name");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!current.olderThanRelease(version)) {
            return Optional.empty();
        }
        return Optional.of(new AvailableUpdate(version, version, tag, text(release, "body"),
                instant(release, "published_at")));
    }

    private Optional<AvailableUpdate> latestMain(AppVersion current) {
        JsonNode runs;
        try {
            runs = getJson("/actions/workflows/release.yml/runs?branch=main&status=success&per_page=1");
        } catch (HttpClientErrorException.NotFound e) {
            throw new UpdateSourceException(token.get().isEmpty() ? privateRepoMessage()
                    : tokenRightsMessage("Actions"), e);
        }
        JsonNode run = runs.path("workflow_runs").path(0);
        String sha = text(run, "head_sha");
        if (sha.length() < 7) {
            return Optional.empty();
        }
        String notes = "";
        Optional<String> running = current.commit();
        if (running.isPresent()) {
            if (sha.startsWith(running.get())) {
                return Optional.empty();
            }
            try {
                JsonNode compare = getJson("/compare/" + running.get() + "..." + sha);
                String status = text(compare, "status");
                if (status.equals("behind") || status.equals("identical")) {
                    return Optional.empty();
                }
                notes = StreamSupport.stream(compare.path("commits").spliterator(), false)
                        .map(c -> "- " + text(c.path("commit"), "message").lines().findFirst().orElse(""))
                        .collect(Collectors.joining("\n"));
            } catch (HttpClientErrorException.NotFound e) {
                // laufender Stand ist GitHub unbekannt (z. B. umgeschriebene Historie): ohne Änderungsliste anbieten
            }
        }
        String shortSha = sha.substring(0, 7);
        return Optional.of(new AvailableUpdate("0.0.0-" + shortSha, "sha-" + shortSha, sha, notes,
                instant(run, "updated_at")));
    }

    /** GitHub meldet fehlende Rechte an privaten Repositories als 404; Contents lesen zeigt, ob der Token sie hat. */
    private boolean canReadContents() {
        try {
            getJson("/contents");
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    private JsonNode getJson(String path) {
        String body = call(() -> client.get().uri(path).headers(this::auth)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json").retrieve().body(String.class));
        return json.readTree(body == null ? "{}" : body);
    }

    private void auth(HttpHeaders headers) {
        token.get().ifPresent(headers::setBearerAuth);
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw e;
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new UpdateSourceException("GitHub: Zugriff verweigert – GitHub-Token prüfen.", e);
            }
            throw new UpdateSourceException("GitHub antwortet mit Fehler " + status.value() + ".", e);
        } catch (ResourceAccessException e) {
            throw new UpdateSourceException("GitHub ist nicht erreichbar.", e);
        } catch (RestClientException e) {
            throw new UpdateSourceException("GitHub antwortet unerwartet: " + e.getMessage(), e);
        }
    }

    private static String privateRepoMessage() {
        return "Das Repository wurde nicht gefunden. Ist es privat, bitte einen GitHub-Token hinterlegen.";
    }

    private static String tokenRightsMessage(String right) {
        return "Nicht gefunden – dem GitHub-Token fehlt vermutlich der Lesezugriff auf „" + right
                + "“ für chrisbad/nestwerk.";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : "";
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value.isEmpty() ? null : Instant.parse(value);
    }
}
