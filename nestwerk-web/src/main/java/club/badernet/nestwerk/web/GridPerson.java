package club.badernet.nestwerk.web;

/**
 * A person column of the time grid split by person: a family member or "Alle" ({@code memberId == null}) for
 * entries without a person.
 */
public record GridPerson(Long memberId, String name, String color) {

    static final GridPerson ALL = new GridPerson(null, "Alle", null);

    public boolean isAll() {
        return memberId == null;
    }
}
