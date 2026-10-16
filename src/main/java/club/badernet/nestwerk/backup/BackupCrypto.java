package club.badernet.nestwerk.backup;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Verschlüsselt Sicherungen mit einer Passphrase: PBKDF2-HMAC-SHA256 und AES-256-GCM in Blöcken zu 1 MiB, damit
 * auch große Dateien nicht in den Speicher müssen. Jeder Block hat eine eigene Nonce (Präfix + Zähler) und trägt
 * im AAD, ob er der letzte ist; Abschneiden oder Vertauschen fällt dadurch auf.
 *
 * <p>Format: {@code NWENC1} | Salz (16) | Iterationen (int) | Nonce-Präfix (8) | Blöcke: Länge (int) + Daten.
 */
public final class BackupCrypto {

    static final byte[] MAGIC = "NWENC1".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK = 1024 * 1024;
    private static final int ITERATIONS = 310_000;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {
    }

    /** Ist der Anfang der Datei das Kennzeichen einer verschlüsselten Sicherung? */
    public static boolean isEncrypted(byte[] head) {
        return head.length >= MAGIC.length && Arrays.equals(Arrays.copyOf(head, MAGIC.length), MAGIC);
    }

    public static void encrypt(InputStream in, OutputStream target, String passphrase) throws IOException {
        requirePassphrase(passphrase);
        byte[] salt = random(16);
        byte[] prefix = random(8);
        SecretKeySpec key = key(passphrase, salt, ITERATIONS);
        DataOutputStream out = new DataOutputStream(target);
        out.write(MAGIC);
        out.write(salt);
        out.writeInt(ITERATIONS);
        out.write(prefix);
        byte[] current = in.readNBytes(CHUNK);
        int counter = 0;
        while (true) {
            byte[] next = current.length == CHUNK ? in.readNBytes(CHUNK) : new byte[0];
            boolean last = next.length == 0;
            byte[] sealed = crypt(Cipher.ENCRYPT_MODE, key, prefix, counter++, last, current);
            out.writeInt(sealed.length);
            out.write(sealed);
            if (last) {
                break;
            }
            current = next;
        }
        out.flush();
    }

    /** Entschlüsselt; eine falsche Passphrase oder veränderte Daten ergeben eine {@link InvalidBackupException}. */
    public static void decrypt(InputStream source, OutputStream out, String passphrase) throws IOException {
        requirePassphrase(passphrase);
        DataInputStream in = new DataInputStream(source);
        byte[] magic = in.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new InvalidBackupException("Die Datei ist keine verschlüsselte Nestwerk-Sicherung.");
        }
        try {
            byte[] salt = in.readNBytes(16);
            int iterations = in.readInt();
            if (iterations < 100_000 || iterations > 10_000_000) {
                throw new InvalidBackupException("Die verschlüsselte Sicherung ist beschädigt.");
            }
            byte[] prefix = in.readNBytes(8);
            SecretKeySpec key = key(passphrase, salt, iterations);
            int counter = 0;
            int length = checkLength(in.readInt());
            while (true) {
                byte[] sealed = in.readNBytes(length);
                if (sealed.length != length) {
                    throw new InvalidBackupException("Die verschlüsselte Sicherung ist unvollständig.");
                }
                byte[] next = in.readNBytes(4);
                boolean last = next.length == 0;
                if (!last && next.length != 4) {
                    throw new InvalidBackupException("Die verschlüsselte Sicherung ist unvollständig.");
                }
                out.write(crypt(Cipher.DECRYPT_MODE, key, prefix, counter++, last, sealed));
                if (last) {
                    return;
                }
                length = checkLength(ByteBuffer.wrap(next).getInt());
            }
        } catch (EOFException e) {
            throw new InvalidBackupException("Die verschlüsselte Sicherung ist unvollständig.");
        }
    }

    private static int checkLength(int length) {
        if (length < TAG_BITS / 8 || length > CHUNK + TAG_BITS / 8) {
            throw new InvalidBackupException("Die verschlüsselte Sicherung ist beschädigt.");
        }
        return length;
    }

    private static byte[] crypt(int mode, SecretKeySpec key, byte[] prefix, int counter, boolean last, byte[] data)
            throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter).array();
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(new byte[]{(byte) (last ? 1 : 0)});
            return cipher.doFinal(data);
        } catch (AEADBadTagException e) {
            throw new InvalidBackupException(counter == 0 ? "Falsche Passphrase für die verschlüsselte Sicherung."
                    : "Die verschlüsselte Sicherung ist beschädigt.");
        } catch (GeneralSecurityException e) {
            throw new IOException("Verschlüsselung nicht verfügbar", e);
        }
    }

    private static SecretKeySpec key(String passphrase, byte[] salt, int iterations) throws IOException {
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(raw, "AES");
        } catch (GeneralSecurityException e) {
            throw new IOException("Schlüsselableitung nicht verfügbar", e);
        }
    }

    private static void requirePassphrase(String passphrase) {
        if (passphrase == null || passphrase.length() < 8) {
            throw new IllegalArgumentException("Die Passphrase muss mindestens 8 Zeichen lang sein.");
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
