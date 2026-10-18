package club.badernet.nestwerk.net;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Lädt Abo-Kalender (z. B. Abfuhrkalender der Gemeinde). Nur http/https auf Standard-Ports, keine internen
 * Adressen, keine Weiterleitungen, höchstens 1 MB.
 */
@Component
public class IcalFetcher {

    public static final int MAX_BYTES = 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final boolean allowPrivateHosts;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();

    public IcalFetcher(@Value("${nestwerk.ical.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /** Prüft und normalisiert die Adresse; wirft bei unzulässigen Zielen. */
    public URI validate(String url) {
        String value = url == null ? "" : url.trim();
        if (value.regionMatches(true, 0, "webcal://", 0, 9)) {
            value = "https://" + value.substring(9);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Die Kalender-Adresse ist ungültig.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("Die Kalender-Adresse muss mit https:// oder http:// beginnen.");
        }
        if (value.length() > 500 || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Die Kalender-Adresse ist ungültig.");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !allowPrivateHosts) {
            throw new IllegalArgumentException("Die Kalender-Adresse darf keinen besonderen Port verwenden.");
        }
        if (!allowPrivateHosts) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (isInternal(address)) {
                        throw new IllegalArgumentException("Interne Adressen sind als Kalender-Quelle nicht erlaubt.");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Der Server der Kalender-Adresse ist nicht erreichbar.");
            }
        }
        return uri;
    }

    public static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 (Unique Local)
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 0 || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 (CGNAT)
                || (first == 169 && second == 254) || first >= 224;
    }

    public byte[] fetch(String url) {
        URI uri = validate(url);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Accept", "text/calendar, */*;q=0.5").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 == 3) {
                    throw new IllegalArgumentException("Die Kalender-Adresse leitet weiter. Bitte die endgültige Adresse eintragen.");
                }
                if (response.statusCode() != 200) {
                    throw new IllegalArgumentException("Der Kalender konnte nicht geladen werden (HTTP " + response.statusCode() + ").");
                }
                byte[] data = body.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES) {
                    throw new IllegalArgumentException("Der Kalender ist größer als 1 MB.");
                }
                return data;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Der Kalender konnte nicht geladen werden.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Das Laden des Kalenders wurde abgebrochen.");
        }
    }
}
