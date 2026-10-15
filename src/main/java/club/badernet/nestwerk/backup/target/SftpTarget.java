package club.badernet.nestwerk.backup.target;

import club.badernet.nestwerk.backup.Retention;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * SFTP-Verzeichnis auf einem anderen Server oder NAS. Der Host-Key wird beim ersten Test gelernt und danach streng
 * geprüft; ohne gespeicherten Host-Key wird nichts übertragen.
 */
public class SftpTarget implements BackupTarget {

    /** Gelernter Host-Key: Zeile im known_hosts-Format und Fingerabdruck zur Anzeige. */
    public record HostKeyInfo(String knownHostsLine, String fingerprint) {
    }

    private static final int TIMEOUT_MS = 20_000;

    private final String host;
    private final int port;
    private final String user;
    private final String path;
    private final String password;
    private final String privateKey;
    private final String knownHost;

    public SftpTarget(String host, int port, String user, String path, String password, String privateKey,
                      String knownHost) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.path = path == null || path.isBlank() ? "." : path.replaceAll("/+$", "");
        this.password = password;
        this.privateKey = privateKey;
        this.knownHost = knownHost;
    }

    @Override
    public String label() {
        return "SFTP";
    }

    /** Verbindet ohne Host-Key-Prüfung und liefert den Schlüssel des Servers zum Bestätigen. */
    public HostKeyInfo fetchHostKey() throws IOException {
        JSch jsch = jsch();
        Session session = null;
        try {
            session = session(jsch, false);
            try {
                session.connect(TIMEOUT_MS);
            } catch (JSchException e) {
                if (session.getHostKey() == null) {
                    throw e;
                }
                // Anmeldung fehlgeschlagen, der Host-Key ist aber bekannt
            }
            HostKey key = session.getHostKey();
            String hostPart = port == 22 ? host : "[" + host + "]:" + port;
            return new HostKeyInfo(hostPart + " " + key.getType() + " " + key.getKey(), key.getFingerPrint(jsch));
        } catch (JSchException e) {
            throw new IOException("SFTP: Server nicht erreichbar (" + e.getMessage() + ")", e);
        } finally {
            if (session != null) {
                session.disconnect();
            }
        }
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        DirectoryTarget.requireName(name);
        run(sftp -> {
            mkdirs(sftp);
            String part = path + "/." + name + ".part";
            sftp.put(file.toString(), part);
            try {
                sftp.rm(path + "/" + name);
            } catch (SftpException ignored) {
                // gab es noch nicht
            }
            sftp.rename(part, path + "/" + name);
            return null;
        });
    }

    @Override
    public List<Retention.Item> list() throws IOException {
        return run(sftp -> {
            List<Retention.Item> items = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);
            for (ChannelSftp.LsEntry entry : entries) {
                SftpATTRS attrs = entry.getAttrs();
                if (!attrs.isDir() && DirectoryTarget.NAME.matcher(entry.getFilename()).matches()) {
                    items.add(new Retention.Item(entry.getFilename(), Instant.ofEpochSecond(attrs.getMTime())));
                }
            }
            return items;
        });
    }

    @Override
    public void delete(String name) throws IOException {
        DirectoryTarget.requireName(name);
        run(sftp -> {
            sftp.rm(path + "/" + name);
            return null;
        });
    }

    @Override
    public String test() throws IOException {
        run(sftp -> {
            mkdirs(sftp);
            return null;
        });
        int count = list().size();
        return "Verbunden, " + count + " Sicherung" + (count == 1 ? "" : "en") + " im Verzeichnis.";
    }

    private interface SftpWork<T> {
        T apply(ChannelSftp sftp) throws SftpException, IOException;
    }

    private <T> T run(SftpWork<T> work) throws IOException {
        if (knownHost == null || knownHost.isBlank()) {
            throw new IOException("SFTP: Der Host-Key des Servers ist noch nicht bestätigt. Bitte zuerst „Verbindung "
                    + "testen“ und den Fingerabdruck bestätigen.");
        }
        Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = jsch();
            jsch.setKnownHosts(new ByteArrayInputStream((knownHost + "\n").getBytes(StandardCharsets.UTF_8)));
            session = session(jsch, true);
            session.connect(TIMEOUT_MS);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(TIMEOUT_MS);
            return work.apply(channel);
        } catch (JSchException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("HostKey has been changed") || message.contains("reject HostKey")) {
                throw new IOException("SFTP: Der Host-Key des Servers hat sich geändert. Wenn das gewollt ist, "
                        + "bitte neu bestätigen.", e);
            }
            if (message.contains("Auth fail") || message.contains("Auth cancel")) {
                throw new IOException("SFTP: Anmeldung fehlgeschlagen (Benutzer, Passwort oder Schlüssel prüfen).", e);
            }
            throw new IOException("SFTP: " + message, e);
        } catch (SftpException e) {
            throw new IOException("SFTP: " + e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private void mkdirs(ChannelSftp sftp) throws SftpException {
        if (path.equals(".")) {
            return;
        }
        StringBuilder current = new StringBuilder(path.startsWith("/") ? "/" : "");
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            current.append(part);
            try {
                sftp.stat(current.toString());
            } catch (SftpException e) {
                sftp.mkdir(current.toString());
            }
            current.append('/');
        }
    }

    private JSch jsch() throws IOException {
        JSch jsch = new JSch();
        if (privateKey != null && !privateKey.isBlank()) {
            try {
                jsch.addIdentity("nestwerk", privateKey.getBytes(StandardCharsets.UTF_8), null, null);
            } catch (JSchException e) {
                throw new IOException("SFTP: Der private Schlüssel ist ungültig (" + e.getMessage() + ")", e);
            }
        }
        return jsch;
    }

    private Session session(JSch jsch, boolean strict) throws JSchException {
        Session session = jsch.getSession(user, host, port);
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }
        session.setConfig("StrictHostKeyChecking", strict ? "yes" : "no");
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setTimeout(TIMEOUT_MS);
        return session;
    }
}
