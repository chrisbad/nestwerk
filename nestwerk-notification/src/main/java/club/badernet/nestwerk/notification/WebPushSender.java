package club.badernet.nestwerk.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Schickt verschlüsselte Push-Nachrichten an den Push-Dienst des Browsers. */
@Component
public class WebPushSender {

    public enum Result { SENT, GONE, FAILED }

    /** Ergebnis samt Fehlertext bei {@code FAILED}. */
    public record Outcome(Result result, String error) {
    }

    private final VapidKeys vapid;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public WebPushSender(VapidKeys vapid, @Value("${nestwerk.mail.from:}") String mailFrom,
                         @Value("${nestwerk.base-url:http://localhost:8080}") String baseUrl) {
        this.vapid = vapid;
        this.subject = mailFrom == null || mailFrom.isBlank() ? baseUrl : "mailto:" + mailFrom;
    }

    public Outcome send(PushSubscription sub, String json) {
        try {
            byte[] body = WebPushCrypto.encrypt(json.getBytes(StandardCharsets.UTF_8),
                    Base64.getUrlDecoder().decode(sub.getP256dh()), Base64.getUrlDecoder().decode(sub.getAuth()));
            String jwt = WebPushCrypto.vapidJwt(vapid.keyPair(), WebPushCrypto.audience(sub.getEndpoint()), subject,
                    12 * 3600);
            HttpRequest request = HttpRequest.newBuilder(URI.create(sub.getEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Encoding", "aes128gcm")
                    .header("TTL", "86400")
                    .header("Urgency", "normal")
                    .header("Authorization", "vapid t=" + jwt + ", k=" + vapid.publicKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Outcome(Result.SENT, null);
            }
            if (status == 404 || status == 410) {
                return new Outcome(Result.GONE, "Abo abgelaufen (" + status + ")");
            }
            return new Outcome(Result.FAILED, "Push-Dienst antwortet mit Status " + status);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return new Outcome(Result.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Result.FAILED, "Unterbrochen");
        }
    }

}
