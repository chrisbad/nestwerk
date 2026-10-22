package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class DemoContactArea implements DemoArea {

    private static final String CITY = "Remchingen";
    private static final String POSTAL_CODE = "75196";

    private final ContactService contacts;

    DemoContactArea(ContactService contacts) {
        this.contacts = contacts;
    }

    @Override
    public String name() {
        return "contacts";
    }

    @Override
    public void seed(DemoContext context) {
        List<ContactInput> inputs = List.of(
                contact("Elektro Schneider", "Handwerker", "Elektro Schneider GmbH", "07232 411 22",
                        "info@elektro-schneider.example", "Hauptstraße 12", "Zuverlässig, kommt auch kurzfristig."),
                contact("Sanitär Keller", "Handwerker", "Keller Heizung & Bad", "07232 380 90",
                        "service@sanitaer-keller.example", "Gartenweg 4", "Notdienst: 0171 555 0101"),
                contact("Dr. Fischer", "Arzt", "Kinderarztpraxis Fischer", "07232 602 15",
                        "praxis@kinderarzt-fischer.example", "Bahnhofstraße 3", "Dienstag und Donnerstag bis 18 Uhr."),
                contact("Zahnarzt Dr. Weber", "Arzt", "Zahnarztpraxis Weber", "07232 700 31",
                        "termin@zahnarzt-weber.example", "Schillerstraße 8", "Kontrolle alle 6 Monate."),
                contact("Oma und Opa", "Familie", null, "07231 99 12 34", null, "Waldstraße 21",
                        "Geburtstage nicht vergessen."),
                contact("Tierarzt Dr. Braun", "Arzt", "Tierarztpraxis Braun", "07232 455 60", null, "Feldweg 2",
                        null));
        inputs.forEach(input -> contacts.create(input, context.actor()));
    }

    private static ContactInput contact(String name, String category, String company, String phone, String email,
                                        String street, String note) {
        return new ContactInput(name, category, company, phone, email, street, POSTAL_CODE, CITY, null, note);
    }
}
