package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.settings.AppSettings;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** VAPID-Schlüssel für Web-Push; beim ersten Bedarf erzeugt und in {@code app_settings} gespeichert. */
@Component
public class VapidKeys {

    static final String PUBLIC = "push.vapid.public";
    static final String PRIVATE = "push.vapid.private";

    private final AppSettings settings;
    private volatile KeyPair keys;

    public VapidKeys(AppSettings settings) {
        this.settings = settings;
    }

    /** Öffentlicher Schlüssel im Format, das {@code pushManager.subscribe} erwartet (Base64url, unkomprimiert). */
    public String publicKey() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(WebPushCrypto.encodePoint((ECPublicKey) keyPair().getPublic()));
    }

    public synchronized KeyPair keyPair() {
        if (keys != null) {
            return keys;
        }
        String pub = settings.get(PUBLIC).orElse(null);
        String priv = settings.get(PRIVATE).orElse(null);
        if (pub != null && priv != null) {
            try {
                var publicKey = WebPushCrypto.decodePoint(Base64.getUrlDecoder().decode(pub));
                var privateKey = KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv)));
                keys = new KeyPair(publicKey, privateKey);
                return keys;
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException("Gespeicherte Push-Schlüssel sind ungültig", e);
            }
        }
        KeyPair generated = WebPushCrypto.generateKeyPair();
        settings.set(PUBLIC, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(WebPushCrypto.encodePoint((ECPublicKey) generated.getPublic())));
        settings.set(PRIVATE, Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()));
        keys = generated;
        return keys;
    }
}
