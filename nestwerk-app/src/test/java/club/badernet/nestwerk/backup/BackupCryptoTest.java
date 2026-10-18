package club.badernet.nestwerk.backup;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupCryptoTest {

    private static final String PASS = "korrekt-pferd-batterie";

    private static byte[] encrypt(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupCrypto.encrypt(new ByteArrayInputStream(data), out, PASS);
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] data, String pass) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupCrypto.decrypt(new ByteArrayInputStream(data), out, pass);
        return out.toByteArray();
    }

    @Test
    void roundTripsEmptySmallAndMultiChunkData() throws IOException {
        byte[] big = new byte[3 * 1024 * 1024 + 17];
        new Random(42).nextBytes(big);
        byte[] exact = Arrays.copyOf(big, 1024 * 1024);
        for (byte[] data : new byte[][]{new byte[0], "Hallo Nestwerk".getBytes(), exact, big}) {
            byte[] sealed = encrypt(data);
            assertThat(BackupCrypto.isEncrypted(sealed)).isTrue();
            assertThat(decrypt(sealed, PASS)).isEqualTo(data);
        }
    }

    @Test
    void wrongPassphraseIsReported() throws IOException {
        byte[] sealed = encrypt("geheim".getBytes());

        assertThatThrownBy(() -> decrypt(sealed, "falsche-passphrase"))
                .isInstanceOf(InvalidBackupException.class).hasMessageContaining("Falsche Passphrase");
    }

    @Test
    void truncationAndTamperingAreDetected() throws IOException {
        byte[] data = new byte[2 * 1024 * 1024 + 5];
        new Random(7).nextBytes(data);
        byte[] sealed = encrypt(data);

        // den letzten Block komplett abschneiden: der vorletzte war nicht als letzter versiegelt
        int lastChunk = 4 + (data.length - 2 * 1024 * 1024) + 16;
        byte[] truncated = Arrays.copyOf(sealed, sealed.length - lastChunk);
        assertThatThrownBy(() -> decrypt(truncated, PASS)).isInstanceOf(InvalidBackupException.class);

        byte[] tampered = sealed.clone();
        tampered[tampered.length - 20] ^= 1;
        assertThatThrownBy(() -> decrypt(tampered, PASS)).isInstanceOf(InvalidBackupException.class);

        assertThatThrownBy(() -> decrypt("PK kein Archiv".getBytes(), PASS))
                .isInstanceOf(InvalidBackupException.class);
    }

    @Test
    void shortPassphraseIsRejected() {
        assertThatThrownBy(() -> BackupCrypto.encrypt(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), "kurz")).isInstanceOf(IllegalArgumentException.class);
    }
}
