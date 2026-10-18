package club.badernet.nestwerk.backup.target;

import club.badernet.nestwerk.backup.Retention;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupTargetTest {

    @TempDir Path temp;

    Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = Files.writeString(temp.resolve("quelle.zip"), "Inhalt der Sicherung");
    }

    // --- Externe Platte --------------------------------------------------------------

    @Test
    void directoryRequiresMarkerBeforeWriting() throws IOException {
        Path disk = Files.createDirectory(temp.resolve("platte"));
        DirectoryTarget target = new DirectoryTarget(disk);

        assertThat(target.isPrepared()).isFalse();
        assertThatThrownBy(() -> target.upload(file, "nestwerk-20261016-023000.zip"))
                .isInstanceOf(IOException.class).hasMessageContaining("nicht eingehängt");
        assertThat(Files.list(disk)).isEmpty();

        target.prepare();
        target.upload(file, "nestwerk-20261016-023000.zip");
        target.upload(file, "nestwerk-20261015-023000.zip.enc");

        assertThat(target.list()).extracting(Retention.Item::name)
                .containsExactlyInAnyOrder("nestwerk-20261016-023000.zip", "nestwerk-20261015-023000.zip.enc");
        assertThat(target.test()).startsWith("Beschreibbar");
        target.delete("nestwerk-20261015-023000.zip.enc");
        assertThat(target.list()).hasSize(1);
        assertThatThrownBy(() -> target.delete("../quelle.zip")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingDirectoryCannotBePrepared() {
        DirectoryTarget target = new DirectoryTarget(temp.resolve("gibtesnicht"));

        assertThatThrownBy(target::prepare).isInstanceOf(IOException.class);
    }

    // --- WebDAV ------------------------------------------------------------------------

    /** Minimaler WebDAV-Server im Speicher: MKCOL, PUT, MOVE, PROPFIND (Tiefe 1), DELETE mit Basic-Auth. */
    static final class FakeDav {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        boolean folder;
        final HttpServer server;
        final String auth = "Basic " + Base64.getEncoder().encodeToString("anna:geheim".getBytes());

        FakeDav() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/dav/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/Nestwerk/";
        }

        private void handle(HttpExchange ex) throws IOException {
            if (!auth.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, "");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String name = path.substring("/dav/Nestwerk/".length());
            switch (ex.getRequestMethod()) {
                case "MKCOL" -> {
                    int code = folder ? 405 : 201;
                    folder = true;
                    respond(ex, code, "");
                }
                case "PUT" -> {
                    files.put(name, ex.getRequestBody().readAllBytes());
                    respond(ex, 201, "");
                }
                case "MOVE" -> {
                    String dest = URI.create(ex.getRequestHeaders().getFirst("Destination")).getPath()
                            .substring("/dav/Nestwerk/".length());
                    files.put(dest, files.remove(name));
                    respond(ex, 201, "");
                }
                case "DELETE" -> respond(ex, files.remove(name) != null ? 204 : 404, "");
                case "PROPFIND" -> {
                    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"
                            + "<d:response><d:href>/dav/Nestwerk/</d:href></d:response>");
                    new TreeMap<>(files).keySet().forEach(n -> xml.append("<d:response><d:href>/dav/Nestwerk/")
                            .append(n).append("</d:href><d:propstat><d:prop><d:getlastmodified>")
                            .append("Fri, 16 Oct 2026 02:30:00 GMT</d:getlastmodified></d:prop></d:propstat>")
                            .append("</d:response>"));
                    respond(ex, 207, xml.append("</d:multistatus>").toString());
                }
                default -> respond(ex, 405, "");
            }
        }

        private static void respond(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        }
    }

    FakeDav dav;

    @AfterEach
    void stopDav() {
        if (dav != null) {
            dav.server.stop(0);
        }
    }

    @Test
    void webDavUploadsListsAndDeletes() throws IOException {
        dav = new FakeDav();
        WebDavTarget target = new WebDavTarget(dav.url(), "anna", "geheim");

        assertThat(target.test()).startsWith("Verbunden, 0 Sicherungen");
        target.upload(file, "nestwerk-20261016-023000.zip");

        assertThat(dav.files).containsOnlyKeys("nestwerk-20261016-023000.zip");
        assertThat(new String(dav.files.get("nestwerk-20261016-023000.zip"))).isEqualTo("Inhalt der Sicherung");
        assertThat(target.list()).extracting(Retention.Item::name).containsExactly("nestwerk-20261016-023000.zip");
        target.delete("nestwerk-20261016-023000.zip");
        assertThat(dav.files).isEmpty();
    }

    @Test
    void webDavReportsWrongCredentials() throws IOException {
        dav = new FakeDav();
        WebDavTarget target = new WebDavTarget(dav.url(), "anna", "falsch");

        assertThatThrownBy(target::test).isInstanceOf(IOException.class).hasMessageContaining("401")
                .hasMessageContaining("Passwort");
    }
}
