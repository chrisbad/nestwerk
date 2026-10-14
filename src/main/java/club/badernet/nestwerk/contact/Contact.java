package club.badernet.nestwerk.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Ein freier Kontakt außerhalb der Familie, z. B. Handwerker oder Schule. */
@Entity
@Table(name = "contacts")
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String category;

    @Column(length = 150)
    private String company;

    @Column(length = 50)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(length = 200)
    private String street;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(length = 100)
    private String city;

    @Column(length = 500)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private int version;

    protected Contact() {
    }

    public Contact(String name, Long createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    public void change(ContactInput in) {
        this.name = in.name();
        this.category = in.category();
        this.company = in.company();
        this.phone = in.phone();
        this.email = in.email();
        this.street = in.street();
        this.postalCode = in.postalCode();
        this.city = in.city();
        this.url = in.url();
        this.note = in.note();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getCompany() { return company; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getStreet() { return street; }
    public String getPostalCode() { return postalCode; }
    public String getCity() { return city; }
    public String getUrl() { return url; }
    public String getNote() { return note; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

    /** {@code tel:}-Link ohne Leer- und Trennzeichen. */
    public String phoneHref() {
        return phone == null ? null : "tel:" + phone.replaceAll("[^0-9+]", "");
    }

    public boolean hasAddress() {
        return street != null || postalCode != null || city != null;
    }

    /** Anschrift in einer Zeile, z. B. für den Kartenlink. */
    public String addressLine() {
        StringBuilder sb = new StringBuilder();
        if (street != null) {
            sb.append(street);
        }
        String place = ((postalCode == null ? "" : postalCode) + " " + (city == null ? "" : city)).trim();
        if (!place.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(place);
        }
        return sb.toString();
    }
}
