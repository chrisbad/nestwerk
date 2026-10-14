package club.badernet.nestwerk.notification;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web-Push mit JDK-Mitteln: Verschlüsselung nach RFC 8291 ({@code aes128gcm}) und VAPID-Anmeldung
 * nach RFC 8292 (ES256-JWT). Schlüssel sind P-256.
 */
public final class WebPushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RECORD_SIZE = 4096;

    private WebPushCrypto() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Öffentlicher Schlüssel als unkomprimierter Punkt (65 Bytes). */
    public static byte[] encodePoint(ECPublicKey key) {
        byte[] x = unsigned(key.getW().getAffineX());
        byte[] y = unsigned(key.getW().getAffineY());
        return concat(new byte[]{4}, x, y);
    }

    public static ECPublicKey decodePoint(byte[] point) {
        if (point.length != 65 || point[0] != 4) {
            throw new IllegalArgumentException("Ungültiger öffentlicher Schlüssel");
        }
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
            ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                    new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, spec));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Ungültiger öffentlicher Schlüssel", e);
        }
    }

    /** Verschlüsselt die Nutzlast für ein Abo ({@code p256dh}, {@code auth}); Ergebnis ist der HTTP-Body. */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) {
        try {
            KeyPair as = generateKeyPair();
            byte[] asPublic = encodePoint((ECPublicKey) as.getPublic());
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(as.getPrivate());
            ka.doPhase(decodePoint(uaPublic), true);
            byte[] shared = ka.generateSecret();

            byte[] prkKey = hmac(authSecret, shared);
            byte[] ikm = hmac(prkKey, concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic,
                    asPublic, new byte[]{1}));
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0"
                    .getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0"
                    .getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] cipherText = cipher.doFinal(concat(payload, new byte[]{2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), cipherText);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Verschlüsselung fehlgeschlagen", e);
        }
    }

    /** VAPID-JWT für den Push-Dienst ({@code aud} = Herkunft des Endpunkts). */
    public static String vapidJwt(KeyPair server, String audience, String subject, long validSeconds) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().getEpochSecond() + validSeconds;
        String claims = b64.encodeToString(("{\"aud\":\"" + jsonEscape(audience) + "\",\"exp\":" + exp
                + ",\"sub\":\"" + jsonEscape(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(server.getPrivate());
            signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            return header + "." + claims + "." + b64.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String audience(String endpoint) {
        URI uri = URI.create(endpoint);
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** 32 Bytes ohne Vorzeichenbyte, links mit Nullen aufgefüllt. */
    private static byte[] unsigned(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
