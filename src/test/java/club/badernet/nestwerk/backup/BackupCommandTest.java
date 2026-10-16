package club.badernet.nestwerk.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackupCommandTest {

    @TempDir
    Path dir;

    @Test
    void writesFileNameForTheUpdater() throws IOException {
        BackupRun run = new BackupRun(BackupRun.Kind.PRE_UPDATE, Instant.now(), null);
        run.finish(BackupRun.Status.SUCCESS, Instant.now(), "nestwerk-x.zip", 10L, "ok");
        Path result = dir.resolve("job/backup");

        BackupCommand.writeResult(result, run);

        assertThat(Files.readString(result, StandardCharsets.UTF_8)).isEqualTo("nestwerk-x.zip");
    }
}
