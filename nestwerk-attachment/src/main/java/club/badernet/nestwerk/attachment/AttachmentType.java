package club.badernet.nestwerk.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Positivliste erlaubter Dateitypen mit Erkennung am Dateianfang (Magic Bytes). */
public enum AttachmentType {
    PNG("image/png", List.of("png"), Magic.PNG),
    JPEG("image/jpeg", List.of("jpg", "jpeg"), Magic.JPEG),
    GIF("image/gif", List.of("gif"), Magic.GIF),
    WEBP("image/webp", List.of("webp"), Magic.WEBP),
    PDF("application/pdf", List.of("pdf"), Magic.PDF),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of("docx"), Magic.ZIP),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", List.of("xlsx"), Magic.ZIP),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", List.of("pptx"), Magic.ZIP),
    ODT("application/vnd.oasis.opendocument.text", List.of("odt"), Magic.ZIP),
    ODS("application/vnd.oasis.opendocument.spreadsheet", List.of("ods"), Magic.ZIP),
    DOC("application/msword", List.of("doc"), Magic.OLE),
    XLS("application/vnd.ms-excel", List.of("xls"), Magic.OLE),
    PPT("application/vnd.ms-powerpoint", List.of("ppt"), Magic.OLE),
    TXT("text/plain;charset=UTF-8", List.of("txt"), Magic.TEXT),
    CSV("text/csv;charset=UTF-8", List.of("csv"), Magic.TEXT);

    private final String contentType;
    private final List<String> extensions;
    private final Magic magic;

    AttachmentType(String contentType, List<String> extensions, Magic magic) {
        this.contentType = contentType;
        this.extensions = extensions;
        this.magic = magic;
    }

    public String contentType() {
        return contentType;
    }

    /** Bilder und PDF dürfen im Browser angezeigt werden, alles andere wird heruntergeladen. */
    public static boolean inline(String contentType) {
        return contentType.startsWith("image/") || contentType.equals("application/pdf");
    }

    public static Optional<AttachmentType> byFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.extensions.contains(ext)).findFirst();
    }

    /** Passt der Dateianfang zum Typ? {@code head} sind die ersten (bis zu 8 KB) Bytes. */
    public boolean matches(byte[] head) {
        return magic.test(head);
    }

    private enum Magic {
        PNG {
            boolean test(byte[] h) {
                return startsWith(h, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            }
        },
        JPEG {
            boolean test(byte[] h) {
                return startsWith(h, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            }
        },
        GIF {
            boolean test(byte[] h) {
                return startsWith(h, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                        || startsWith(h, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            }
        },
        WEBP {
            boolean test(byte[] h) {
                return h.length >= 12 && startsWith(h, "RIFF".getBytes(StandardCharsets.US_ASCII))
                        && new String(h, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            }
        },
        PDF {
            boolean test(byte[] h) {
                return startsWith(h, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            }
        },
        ZIP {
            boolean test(byte[] h) {
                return startsWith(h, new byte[]{'P', 'K', 3, 4});
            }
        },
        OLE {
            boolean test(byte[] h) {
                return startsWith(h, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
            }
        },
        /** Text: keine NUL-Bytes und kein HTML-Anfang. */
        TEXT {
            boolean test(byte[] h) {
                for (byte b : h) {
                    if (b == 0) {
                        return false;
                    }
                }
                String start = new String(h, 0, Math.min(h.length, 64), StandardCharsets.UTF_8).stripLeading()
                        .toLowerCase(Locale.ROOT);
                return !start.startsWith("<!doctype html") && !start.startsWith("<html") && !start.startsWith("<script");
            }
        };

        abstract boolean test(byte[] head);

        static boolean startsWith(byte[] data, byte[] prefix) {
            if (data.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (data[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
