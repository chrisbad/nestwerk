package club.badernet.nestwerk.web;

import club.badernet.nestwerk.todo.AssigneeRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Dragging an entry from one person's column to another's in the calendar: {@code to} takes the place of
 * {@code from}, other people stay. {@code null} stands for the column "Alle" (nobody).
 */
record MemberSwap(Long from, Long to) {

    boolean changesSomething() {
        return !Objects.equals(from, to);
    }

    List<AssigneeRef> apply(List<AssigneeRef> assignees) {
        if (!changesSomething()) {
            return assignees;
        }
        Set<AssigneeRef> result = new LinkedHashSet<>(assignees);
        if (from != null) {
            result.remove(AssigneeRef.member(from));
        }
        if (to != null) {
            result.add(AssigneeRef.member(to));
        }
        return List.copyOf(result);
    }
}
