package club.badernet.nestwerk.web;

import java.util.List;

/**
 * The personal agenda of one day: {@code entries} of that day in order, {@code overdue} to-dos and chores that were
 * due earlier (only filled for today) and whether the viewer's account is linked to a family member.
 */
public record MyDay(List<MyDayEntry> entries, List<MyDayEntry> overdue, boolean memberLinked) {

    public boolean isEmpty() {
        return entries.isEmpty() && overdue.isEmpty();
    }
}
