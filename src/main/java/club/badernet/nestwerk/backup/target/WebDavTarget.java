package club.badernet.nestwerk.backup.target;

import club.badernet.nestwerk.backup.Retention;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** WebDAV-Ordner, z. B. in einer Nextcloud ({@code https://cloud/remote.php/dav/files/<user>/Nestwerk/}). */
public class WebDavTarget implements BackupTarget {

    private static final String PROPFIND = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>
            """;

    private final URI folder;
    private final String authorization;
    private final HttpClient http;

    public WebDavTarget(String folderUrl, String user, String password) {
        this.folder = URI.create(folderUrl.endsWith("/") ? folderUrl : folderUrl + "/");
        this.authorization = user == null || user.isBlank() ? null : "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String label() {
        return "WebDAV";
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        DirectoryTarget.requireName(name);
        ensureFolder();
        URI part = folder.resolve("." + name + ".part");
        expect(send(request(part).timeout(Duration.ofHours(6)).PUT(HttpRequest.BodyPublishers.ofFile(file))),
                "Hochladen", 200, 201, 204);
        HttpResponse<byte[]> moved = send(request(part).method("MOVE", HttpRequest.BodyPublishers.noBody())
                .header("Destination", folder.resolve(name).toString()).header("Overwrite", "T"));
        expect(moved, "Umbenennen", 201, 204);
    }

    @Override
    public List<Retention.Item> list() throws IOException {
        HttpResponse<byte[]> response = send(request(folder).header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND)));
        expect(response, "Auflisten", 207);
        return parse(response.body());
    }

    @Override
    public void delete(String name) throws IOException {
        HttpResponse<byte[]> response = send(request(folder.resolve(DirectoryTarget.requireName(name))).DELETE());
        expect(response, "Löschen", 200, 204, 404);
    }

    @Override
    public String test() throws IOException {
        ensureFolder();
        int count = list().size();
        return "Verbunden, " + count + " Sicherung" + (count == 1 ? "" : "en") + " im Ordner.";
    }

    private void ensureFolder() throws IOException {
        HttpResponse<byte[]> response = send(request(folder).method("MKCOL", HttpRequest.BodyPublishers.noBody()));
        // 201 neu angelegt, 405 existiert bereits
        expect(response, "Ordner anlegen", 201, 405);
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen", e);
        }
    }

    private static void expect(HttpResponse<byte[]> response, String action, int... ok) throws IOException {
        for (int code : ok) {
            if (response.statusCode() == code) {
                return;
            }
        }
        String hint = switch (response.statusCode()) {
            case 401, 403 -> " (Benutzer oder Passwort falsch?)";
            case 404, 409 -> " (Ordner nicht gefunden?)";
            case 507 -> " (kein Speicherplatz mehr)";
            default -> "";
        };
        throw new IOException("WebDAV: " + action + " fehlgeschlagen, HTTP " + response.statusCode() + hint);
    }

    List<Retention.Item> parse(byte[] xml) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("WebDAV: unverständliche Antwort des Servers", e);
        }
        List<Retention.Item> items = new ArrayList<>();
        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);
            String href = text(response, "href");
            if (href == null) {
                continue;
            }
            String path = URLDecoder.decode(href, StandardCharsets.UTF_8);
            String name = path.substring(path.lastIndexOf('/', path.length() - 2) + 1);
            if (!DirectoryTarget.NAME.matcher(name).matches()) {
                continue;
            }
            items.add(new Retention.Item(name, modified(text(response, "getlastmodified"))));
        }
        return items;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS("DAV:", tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static Instant modified(String value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
