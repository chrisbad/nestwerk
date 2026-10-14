package club.badernet.nestwerk.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushEndpointSafetyTest {

    private final PushSubscriptions strict = new PushSubscriptions(null, false);

    @Test
    void acceptsPublicHttpsPushServices() {
        assertThatCode(() -> strict.requireSafeEndpoint("https://fcm.googleapis.com/fcm/send/abc"))
                .doesNotThrowAnyException();
        assertThatCode(() -> strict.requireSafeEndpoint("https://updates.push.services.mozilla.com/wpush/v2/x"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOnlyKnownPushServicesOnDefaultPort() {
        assertThatCode(() -> strict.requireSafeEndpoint("https://web.push.apple.com/QGx"))
                .doesNotThrowAnyException();
        assertThatCode(() -> strict.requireSafeEndpoint("https://wns2-par02p.notify.windows.com/w/?token=x"))
                .doesNotThrowAnyException();
        for (String bad : new String[]{"https://push.example.org/x", "https://fcm.googleapis.com.evil.org/x",
                "https://evilgoogleapis.com/x", "https://fcm.googleapis.com:8443/x"}) {
            assertThatThrownBy(() -> strict.requireSafeEndpoint(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInternalOrInsecureTargets() {
        for (String bad : new String[]{"http://fcm.googleapis.com/x", "https://localhost/x", "https://127.0.0.1/x",
                "https://10.0.0.5/push", "https://[::1]/x", "https://db/x", "https://nas.local/x", "file:///etc/passwd",
                "kaputt"}) {
            assertThatThrownBy(() -> strict.requireSafeEndpoint(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
