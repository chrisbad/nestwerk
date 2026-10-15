package club.badernet.nestwerk.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Verschlüsselt gespeicherte Zugangsdaten (SFTP/WebDAV-Passwort, Passphrase) mit AES-256-GCM. Der Schlüssel kommt
 * aus {@code NESTWERK_SECRET_KEY}; fehlt er, legt Nestwerk eine Schlüsseldatei im Sicherungsverzeichnis an.
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String configured;
    private final Path keyFile;
    private volatile SecretKeySpec key;

    public SecretBox(@Value("${nestwerk.secret-key:}") String configured,
                     @Value("${nestwerk.backup.dir:./data/backups}") String backupDir) {
        this.configured = configured;
        this.keyFile = Path.of(backupDir).toAbsolutePath().normalize().resolve(".secret.key");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Entschlüsselt; passt der Schlüssel nicht (z. B. nach Umzug ohne Schlüssel), ist das Ergebnis leer. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, raw, 0, 12));
            return new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Gespeichertes Geheimnis lässt sich nicht entschlüsseln (anderer NESTWERK_SECRET_KEY?)");
            return null;
        }
    }

    private SecretKeySpec key() {
        SecretKeySpec current = key;
        if (current == null) {
            synchronized (this) {
                if (key == null) {
                    key = new SecretKeySpec(sha256(material()), "AES");
                }
                current = key;
            }
        }
        return current;
    }

    private byte[] material() {
        if (configured != null && !configured.isBlank()) {
            return configured.trim().getBytes(StandardCharsets.UTF_8);
        }
        try {
            if (Files.isRegularFile(keyFile)) {
                return Files.readAllBytes(keyFile);
            }
            Files.createDirectories(keyFile.getParent());
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            byte[] text = Base64.getEncoder().encode(generated);
            Files.write(keyFile, text);
            log.warn("NESTWERK_SECRET_KEY ist nicht gesetzt; Schlüssel wurde in {} erzeugt", keyFile);
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("Schlüsseldatei nicht verfügbar", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
