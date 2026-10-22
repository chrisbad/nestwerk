package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.user.User;

import java.time.LocalDate;

/** What every demo area needs: the acting administrator, the family and a fixed notion of "today". */
record DemoContext(User actor, DemoFamily family, LocalDate today) {

    /** The Monday of the current week; demo dates are placed relative to it. */
    LocalDate monday() {
        return today.minusDays(today.getDayOfWeek().getValue() - 1L);
    }
}
