package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreKind;

/**
 * A chore in the list, with what the viewer may do with it so the template needs no permission logic.
 *
 * @param area      area to show, {@link #NO_AREA} when the chore has none
 * @param areaKey   {@code area} without commas, usable as filter value in the address of the table
 * @param schedule  readable rhythm, e.g. "Wöchentlich, 18:00 Uhr"
 * @param distribution who gets the chore; empty for chores "on demand"
 */
public record ChoreRow(Chore chore, String area, String areaKey, String schedule, String distribution,
                       boolean canBook, boolean canEdit, boolean canDelete) {

    static final String NO_AREA = "Ohne Bereich";

    static ChoreRow of(Chore chore, boolean canBook, boolean canEdit, boolean canDelete) {
        String area = chore.getArea() == null || chore.getArea().isBlank() ? NO_AREA : chore.getArea();
        String distribution = chore.getKind() == ChoreKind.ON_DEMAND ? "" : chore.getDistribution().label();
        return new ChoreRow(chore, area, area.replace(',', ' '), HouseholdViews.schedule(chore), distribution,
                canBook, canEdit, canDelete);
    }
}
