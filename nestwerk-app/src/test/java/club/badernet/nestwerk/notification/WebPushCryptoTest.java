package club.badernet.nestwerk.notification;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushCryptoTest {

    @Test
    void encryptedPayloadCanBeDecryptedLikeABrowser() throws Exception {
        KeyPair ua = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePoint((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        byte[] payload = "{\"title\":\"Steuer\",\"body\":\"bald fällig\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPushCrypto.encrypt(payload, uaPublic, auth);

        // Empfängerseite nach RFC 8291
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int rs = in.getInt();
        int idLen = in.get() & 0xff;
        byte[] asPublic = new byte[idLen];
        in.get(asPublic);
        byte[] cipherText = new byte[in.remaining()];
        in.get(cipherText);
        assertThat(rs).isEqualTo(4096);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ua.getPrivate());
        ka.doPhase(WebPushCrypto.decodePoint(asPublic), true);
        byte[] shared = ka.generateSecret();
        byte[] prkKey = WebPushCrypto.hmac(auth, shared);
        byte[] keyInfo = WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic,
                new byte[]{1});
        byte[] ikm = WebPushCrypto.hmac(prkKey, keyInfo);
        byte[] prk = WebPushCrypto.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk,
                WebPushCrypto.concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk,
                WebPushCrypto.concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(cipherText);

        assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(plain, plain.length - 1)).isEqualTo(payload);
    }

    @Test
    void vapidJwtIsSignedWithTheServerKey() throws Exception {
        KeyPair server = WebPushCrypto.generateKeyPair();

        String jwt = WebPushCrypto.vapidJwt(server, "https://push.example.org", "mailto:nestwerk@example.org", 1_000L);

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://push.example.org\"").contains("\"exp\":")
                .contains("mailto:nestwerk@example.org");
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(server.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void audienceIsTheOriginOfTheEndpoint() {
        assertThat(WebPushCrypto.audience("https://fcm.googleapis.com/fcm/send/abc:def"))
                .isEqualTo("https://fcm.googleapis.com");
        assertThat(WebPushCrypto.audience("http://localhost:9000/push/1")).isEqualTo("http://localhost:9000");
    }
}
