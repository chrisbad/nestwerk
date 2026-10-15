package club.badernet.nestwerk.transfer;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.user.User;
import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.property.Address;
import ezvcard.property.Categories;
import ezvcard.property.Email;
import ezvcard.property.FormattedName;
import ezvcard.property.Note;
import ezvcard.property.Organization;
import ezvcard.property.StructuredName;
import ezvcard.property.Url;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Kontakte als vCard 4.0 oder CSV aus- und einlesen. Beim Import gilt ein Kontakt als vorhanden, wenn Name und
 * (falls angegeben) E-Mail oder Telefon übereinstimmen; dann werden nur die mitgelieferten Felder überschrieben.
 */
@Service
public class ContactTransfer {

    static final List<String> COLUMNS = List.of("Name", "Kategorie", "Firma", "Telefon", "E-Mail", "Straße", "PLZ",
            "Ort", "Webseite", "Notiz");
    static final int MAX_ROWS = 5000;

    /** Spaltennamen (klein geschrieben) aus Nestwerk-, Google- und Outlook-Exporten je Feld. */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "name", List.of("name", "anzeigename", "display name", "full name"),
            "category", List.of("kategorie", "category", "categories", "labels", "group membership"),
            "company", List.of("firma", "company", "organization", "organization name", "organization 1 - name",
                    "unternehmen"),
            "phone", List.of("telefon", "phone", "telephone", "mobile phone", "phone 1 - value", "telefon mobil",
                    "mobiltelefon"),
            "email", List.of("e-mail", "email", "e-mail address", "e-mail 1 - value", "e-mail-adresse"),
            "street", List.of("straße", "strasse", "street", "address 1 - street", "home street", "straße privat"),
            "postal", List.of("plz", "postal code", "zip", "address 1 - postal code", "home postal code",
                    "postleitzahl privat"),
            "city", List.of("ort", "stadt", "city", "address 1 - city", "home city", "ort privat"),
            "url", List.of("webseite", "website", "url", "web page", "website 1 - value"),
            "note", List.of("notiz", "notizen", "note", "notes"));

    private final ContactService contacts;
    private final RowRunner runner;

    public ContactTransfer(ContactService contacts, RowRunner runner) {
        this.contacts = contacts;
        this.runner = runner;
    }

    // --- Export ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public String exportVcf(User viewer) {
        List<VCard> cards = contacts.findVisible(viewer).stream().map(ContactTransfer::card).toList();
        return Ezvcard.write(cards).version(VCardVersion.V4_0).prodId(false).go();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(User viewer) {
        StringBuilder out = new StringBuilder("﻿");
        try (CSVPrinter printer = new CSVPrinter(out, excel())) {
            printer.printRecord(COLUMNS);
            for (Contact c : contacts.findVisible(viewer)) {
                printer.printRecord(c.getName(), c.getCategory(), c.getCompany(), c.getPhone(), c.getEmail(),
                        c.getStreet(), c.getPostalCode(), c.getCity(), c.getUrl(), c.getNote());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static CSVFormat excel() {
        return CSVFormat.EXCEL.builder().setDelimiter(';').setRecordSeparator("\r\n").get();
    }

    private static VCard card(Contact c) {
        VCard card = new VCard();
        card.setFormattedName(c.getName());
        if (c.getCompany() != null) {
            card.setOrganization(c.getCompany());
        }
        if (c.getPhone() != null) {
            card.addTelephoneNumber(c.getPhone());
        }
        if (c.getEmail() != null) {
            card.addEmail(c.getEmail());
        }
        if (c.getStreet() != null || c.getPostalCode() != null || c.getCity() != null) {
            Address address = new Address();
            address.setStreetAddress(c.getStreet());
            address.setPostalCode(c.getPostalCode());
            address.setLocality(c.getCity());
            card.addAddress(address);
        }
        if (c.getUrl() != null) {
            card.addUrl(c.getUrl());
        }
        if (c.getNote() != null) {
            card.addNote(c.getNote());
        }
        if (c.getCategory() != null) {
            card.setCategories(c.getCategory());
        }
        return card;
    }

    // --- Import ------------------------------------------------------------------------

    public ImportReport importVcf(byte[] data, User actor, boolean commit) {
        List<VCard> cards = Ezvcard.parse(new String(data, StandardCharsets.UTF_8)).all();
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("Die Datei enthält keine vCards.");
        }
        return importAll(cards.stream().map(ContactTransfer::input).toList(), actor, commit, List.of());
    }

    public ImportReport importCsv(byte[] data, User actor, boolean commit) {
        String text = new String(data, StandardCharsets.UTF_8).replaceFirst("^﻿", "");
        String header = text.lines().findFirst().orElse("");
        char delimiter = header.chars().filter(ch -> ch == ';').count() >= header.chars().filter(ch -> ch == ',')
                .count() ? ';' : ',';
        List<ContactInput> inputs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try (CSVParser parser = CSVFormat.EXCEL.builder().setDelimiter(delimiter).setHeader()
                .setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).get()
                .parse(new StringReader(text))) {
            Map<String, String> columns = parser.getHeaderNames().stream()
                    .collect(Collectors.toMap(h -> h.toLowerCase(Locale.ROOT).trim(), h -> h, (a, b) -> a));
            Function<String, Optional<String>> column = field -> ALIASES.get(field).stream()
                    .map(columns::get).filter(Objects::nonNull).findFirst();
            Optional<String> name = column.apply("name");
            Optional<String> first = Stream.of("first name", "given name", "vorname").map(columns::get)
                    .filter(Objects::nonNull).findFirst();
            Optional<String> last = Stream.of("last name", "family name", "nachname").map(columns::get)
                    .filter(Objects::nonNull).findFirst();
            if (name.isEmpty() && first.isEmpty() && last.isEmpty()) {
                throw new IllegalArgumentException("Die CSV-Datei braucht eine Spalte „Name“ (oder Vor-/Nachname).");
            }
            for (CSVRecord record : parser) {
                if (inputs.size() >= MAX_ROWS) {
                    throw new IllegalArgumentException("Die Datei enthält mehr als " + MAX_ROWS + " Kontakte.");
                }
                Function<Optional<String>, String> get = c -> c.filter(record::isMapped).map(record::get)
                        .filter(v -> !v.isBlank()).orElse(null);
                String fullName = get.apply(name);
                if (fullName == null) {
                    fullName = Stream.of(get.apply(first), get.apply(last)).filter(Objects::nonNull)
                            .collect(Collectors.joining(" "));
                }
                inputs.add(new ContactInput(fullName, get.apply(column.apply("category")),
                        get.apply(column.apply("company")), get.apply(column.apply("phone")),
                        get.apply(column.apply("email")), get.apply(column.apply("street")),
                        get.apply(column.apply("postal")), get.apply(column.apply("city")),
                        get.apply(column.apply("url")), get.apply(column.apply("note"))));
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("Die CSV-Datei ist nicht lesbar: " + e.getMessage());
        }
        return importAll(inputs, actor, commit, notes);
    }

    private ImportReport importAll(List<ContactInput> inputs, User actor, boolean commit, List<String> notes) {
        if (!contacts.canCreate(actor)) {
            throw new AccessDeniedException("Keine Berechtigung, Kontakte anzulegen");
        }
        List<ImportReport.Row> rows = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        // Dubletten innerhalb der Datei fängt „seen“ ab, daher reicht der Stand vor dem Import
        List<Contact> existing = contacts.findVisible(actor);
        for (ContactInput in : inputs) {
            String label = in.name() == null || in.name().isBlank() ? "(ohne Namen)" : in.name();
            String detail = Stream.of(in.company(), in.phone(), in.email()).filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            String key = key(in);
            if (seen.contains(key)) {
                rows.add(ImportReport.Row.skip(label, detail, "doppelt in der Datei"));
                continue;
            }
            seen.add(key);
            rows.add(runner.run(label, detail, commit, () -> {
                Optional<Contact> match = existing.stream().filter(c -> same(c, in)).findFirst();
                if (match.isPresent()) {
                    if (!contacts.can(actor, "EDIT", match.get())) {
                        return ImportReport.Row.skip(label, detail, "vorhanden, Bearbeiten nicht erlaubt");
                    }
                    contacts.update(match.get().getId(), merge(match.get(), in), actor);
                    return new ImportReport.Row(label, detail, ImportReport.Action.UPDATE, null);
                }
                contacts.create(in, actor);
                return new ImportReport.Row(label, detail, ImportReport.Action.NEW, null);
            }));
        }
        return new ImportReport(rows, notes, commit);
    }

    private static String key(ContactInput in) {
        return (in.name() == null ? "" : in.name().trim().toLowerCase(Locale.GERMAN)) + "|"
                + (in.email() == null ? "" : in.email().trim().toLowerCase(Locale.ROOT)) + "|"
                + (in.phone() == null ? "" : digits(in.phone()));
    }

    private static boolean same(Contact c, ContactInput in) {
        if (in.name() == null || !c.getName().equalsIgnoreCase(in.name().trim())) {
            return false;
        }
        boolean mailGiven = in.email() != null && c.getEmail() != null;
        boolean phoneGiven = in.phone() != null && c.getPhone() != null;
        if (!mailGiven && !phoneGiven) {
            return true;
        }
        return (mailGiven && c.getEmail().equalsIgnoreCase(in.email().trim()))
                || (phoneGiven && digits(c.getPhone()).equals(digits(in.phone())));
    }

    private static String digits(String phone) {
        return phone.replaceAll("[^0-9+]", "");
    }

    /** Vorhandene Werte bleiben, wo der Import nichts liefert. */
    private static ContactInput merge(Contact c, ContactInput in) {
        return new ContactInput(c.getName(), or(in.category(), c.getCategory()), or(in.company(), c.getCompany()),
                or(in.phone(), c.getPhone()), or(in.email(), c.getEmail()), or(in.street(), c.getStreet()),
                or(in.postalCode(), c.getPostalCode()), or(in.city(), c.getCity()), or(in.url(), c.getUrl()),
                or(in.note(), c.getNote()));
    }

    private static String or(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static ContactInput input(VCard card) {
        String name = Optional.ofNullable(card.getFormattedName()).map(FormattedName::getValue)
                .filter(v -> !v.isBlank())
                .orElseGet(() -> {
                    StructuredName n = card.getStructuredName();
                    return n == null ? null : Stream.of(n.getGiven(), n.getFamily()).filter(Objects::nonNull)
                            .collect(Collectors.joining(" "));
                });
        String company = Optional.ofNullable(card.getOrganization()).map(Organization::getValues)
                .filter(v -> !v.isEmpty()).map(v -> v.getFirst()).orElse(null);
        String phone = card.getTelephoneNumbers().stream().map(t -> t.getText() != null ? t.getText()
                : t.getUri() == null ? null : t.getUri().getNumber()).filter(Objects::nonNull).findFirst().orElse(null);
        String email = card.getEmails().stream().map(Email::getValue).filter(Objects::nonNull).findFirst()
                .orElse(null);
        Address address = card.getAddresses().isEmpty() ? null : card.getAddresses().getFirst();
        String url = card.getUrls().stream().map(Url::getValue).filter(Objects::nonNull).findFirst().orElse(null);
        String note = card.getNotes().stream().map(Note::getValue).filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        String category = Optional.ofNullable(card.getCategories()).map(Categories::getValues)
                .filter(v -> !v.isEmpty()).map(v -> String.join(", ", v)).orElse(null);
        return new ContactInput(name, category, company, phone, email,
                address == null ? null : address.getStreetAddress(),
                address == null ? null : address.getPostalCode(), address == null ? null : address.getLocality(),
                url, note.isBlank() ? null : note);
    }
}
