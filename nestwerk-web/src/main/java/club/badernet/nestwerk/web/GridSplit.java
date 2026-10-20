package club.badernet.nestwerk.web;

/** How the columns of a day in the time grid are divided: one column per day or one per person and day. */
public enum GridSplit {

    NONE,
    /** One column per family member plus "Alle" for entries without a person. */
    BY_PERSON;

    /** Cookie that remembers the choice per device, so the server renders the right grid at once. */
    public static final String COOKIE = "nestwerk-calendar-split";
    private static final String BY_PERSON_VALUE = "person";

    public static GridSplit fromCookie(String value) {
        return BY_PERSON_VALUE.equals(value) ? BY_PERSON : NONE;
    }
}
