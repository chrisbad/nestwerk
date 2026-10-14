package club.badernet.nestwerk.contact;

/** Eingabe für Anlegen/Ändern eines Kontakts; leere Felder sind {@code null}. */
public record ContactInput(String name, String category, String company, String phone, String email,
                           String street, String postalCode, String city, String url, String note) {
}
