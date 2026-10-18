package club.badernet.nestwerk.kitchen.integration;

import club.badernet.nestwerk.net.IcalFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lädt Rezeptseiten. Nur http/https auf Standard-Ports, keine internen Adressen, höchstens drei Weiterleitungen (jede
 * wird erneut geprüft), höchstens 3 MB.
 */
@Component
public class PageFetcher {

    static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private final boolean allowPrivateHosts;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();

    public PageFetcher(@Value("${nestwerk.kitchen.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public String fetch(String url) {
        URI uri = validate(url == null ? "" : url.strip());
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                    .header("User-Agent", "Mozilla/5.0 (compatible; Nestwerk Rezeptimport)")
                    .GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status / 100 == 3) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new ImportException("Die Seite leitet ins Leere weiter."));
                        uri = validate(uri.resolve(location).toString());
                        continue;
                    }
                    if (status != 200) {
                        throw new ImportException("Die Seite konnte nicht geladen werden (HTTP " + status + ").");
                    }
                    byte[] data = body.readNBytes(MAX_BYTES + 1);
                    if (data.length > MAX_BYTES) {
                        throw new ImportException("Die Seite ist größer als 3 MB.");
                    }
                    return new String(data, charset(response.headers().firstValue("Content-Type").orElse("")));
                }
            } catch (IOException e) {
                throw new ImportException("Die Seite ist nicht erreichbar.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImportException("Das Laden wurde abgebrochen.");
            }
        }
        throw new ImportException("Die Seite leitet zu oft weiter.");
    }

    URI validate(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new ImportException("Die Adresse ist ungültig.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new ImportException("Die Adresse muss mit https:// oder http:// beginnen.");
        }
        if (value.length() > 2000 || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new ImportException("Die Adresse ist ungültig.");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !allowPrivateHosts) {
            throw new ImportException("Die Adresse darf keinen besonderen Port verwenden.");
        }
        if (!allowPrivateHosts) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (IcalFetcher.isInternal(address)) {
                        throw new ImportException("Interne Adressen sind nicht erlaubt.");
                    }
                }
            } catch (UnknownHostException e) {
                throw new ImportException("Der Server ist nicht erreichbar.");
            }
        }
        return uri;
    }

    private static Charset charset(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (RuntimeException e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
