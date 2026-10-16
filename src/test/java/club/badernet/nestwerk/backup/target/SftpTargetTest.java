package club.badernet.nestwerk.backup.target;

import club.badernet.nestwerk.backup.Retention;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SftpTargetTest {

    @Container
    static final GenericContainer<?> SFTP = new GenericContainer<>("atmoz/sftp:alpine")
            .withCommand("anna:geheim:::upload")
            .withExposedPorts(22);

    @TempDir Path temp;

    private SftpTarget target(String password, String knownHost) {
        return new SftpTarget(SFTP.getHost(), SFTP.getMappedPort(22), "anna", "upload/nestwerk", password, null,
                knownHost);
    }

    @Test
    void learnsHostKeyThenTransfersStrictly() throws IOException {
        assertThatThrownBy(() -> target("geheim", "").test()).isInstanceOf(IOException.class)
                .hasMessageContaining("Host-Key");

        SftpTarget.HostKeyInfo key = target("geheim", "").fetchHostKey();
        assertThat(key.fingerprint()).isNotBlank();
        assertThat(key.knownHostsLine()).startsWith("[" + SFTP.getHost() + "]:" + SFTP.getMappedPort(22) + " ");

        SftpTarget target = target("geheim", key.knownHostsLine());
        assertThat(target.test()).startsWith("Verbunden, 0 Sicherungen");
        Path file = Files.writeString(temp.resolve("x.zip"), "Inhalt");
        target.upload(file, "nestwerk-20261016-023000.zip");
        target.upload(file, "nestwerk-20261016-023000.zip");

        assertThat(target.list()).extracting(Retention.Item::name).containsExactly("nestwerk-20261016-023000.zip");
        target.delete("nestwerk-20261016-023000.zip");
        assertThat(target.list()).isEmpty();
    }

    @Test
    void reportsWrongPasswordAndForeignHostKey() throws IOException {
        SftpTarget.HostKeyInfo key = target("falsch", "").fetchHostKey();

        assertThatThrownBy(() -> target("falsch", key.knownHostsLine()).test())
                .isInstanceOf(IOException.class).hasMessageContaining("Anmeldung fehlgeschlagen");

        String foreign = key.knownHostsLine().replaceFirst(" (\\S+)$", " AAAAC3NzaC1lZDI1NTE5AAAAIB7b3Y8pK2rOq4"
                + "dlJh8qU6m8m1v1Xn2s3XyQkY9ZbN0a");
        assertThatThrownBy(() -> target("geheim", foreign).test()).isInstanceOf(IOException.class);
    }
}
