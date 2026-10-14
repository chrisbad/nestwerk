package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;

/** Formularobjekt für Kontakte. */
public class ContactForm {

    private String name;
    private String category;
    private String company;
    private String phone;
    private String email;
    private String street;
    private String postalCode;
    private String city;
    private String url;
    private String note;

    public static ContactForm from(Contact c) {
        ContactForm f = new ContactForm();
        f.name = c.getName();
        f.category = c.getCategory();
        f.company = c.getCompany();
        f.phone = c.getPhone();
        f.email = c.getEmail();
        f.street = c.getStreet();
        f.postalCode = c.getPostalCode();
        f.city = c.getCity();
        f.url = c.getUrl();
        f.note = c.getNote();
        return f;
    }

    public ContactInput toInput() {
        return new ContactInput(name, category, company, phone, email, street, postalCode, city, url, note);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
