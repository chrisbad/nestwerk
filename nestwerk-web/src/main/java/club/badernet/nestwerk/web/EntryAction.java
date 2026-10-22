package club.badernet.nestwerk.web;

import club.badernet.nestwerk.item.CalendarItem;
import club.badernet.nestwerk.item.ItemAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A one-tap action on a calendar entry, shown in the context menu and in the bar of the dialog. {@code url} is
 * posted with {@code params}; {@code confirm} (if any) is asked first. The key {@link #EDIT} has no URL: it opens
 * the dialog of the entry.
 */
public record EntryAction(String key, String label, String url, Map<String, String> params, String confirm,
                          String confirmLabel, boolean danger) {

    public static final String EDIT = "edit";

    private static final String CHORES = "/calendar/actions/chores/";
    private static final String EVENTS = "/calendar/actions/events/";
    private static final String TODOS = "/calendar/actions/todos/";

    /** Opening the dialog to change the entry. */
    static EntryAction edit() {
        return new EntryAction(EDIT, "Bearbeiten …", null, Map.of(), null, null, false);
    }

    private static EntryAction post(String key, String label, String url) {
        return new EntryAction(key, label, url, Map.of(), null, null, false);
    }

    /**
     * What the viewer may do right on the entry, from the actions the domain services allow for the item.
     * {@code occurrence} is the occurrence of a series the entry shows.
     */
    public static List<EntryAction> of(CalendarItem item, Instant occurrence) {
        List<EntryAction> result = new ArrayList<>();
        switch (item) {
            case CalendarItem.Chore chore -> {
                String base = CHORES + chore.id();
                if (chore.actions().contains(ItemAction.COMPLETE)) {
                    result.add(post("complete", "Abhaken", base + "/complete"));
                }
                if (chore.actions().contains(ItemAction.SKIP)) {
                    result.add(post("skip", "Nicht nötig", base + "/skip"));
                }
                if (chore.actions().contains(ItemAction.UNDO)) {
                    result.add(post("undo", chore.status().equals("SKIPPED") ? "Wieder nötig" : "Rückgängig",
                            base + "/undo"));
                }
                addEdit(result, chore);
            }
            case CalendarItem.Event event -> {
                addEdit(result, event);
                boolean recurring = event.seriesId() != null;
                if (recurring && occurrence != null && event.actions().contains(ItemAction.EDIT)) {
                    result.add(new EntryAction("cancel-occurrence", "Dieses Vorkommen absagen",
                            EVENTS + event.id() + "/occurrence/cancel", Map.of("recurrenceId", occurrence.toString()),
                            "Dieses Vorkommen absagen?", "Absagen", false));
                }
                if (event.actions().contains(ItemAction.DELETE)) {
                    result.add(new EntryAction("delete", recurring ? "Ganze Serie löschen" : "Termin löschen",
                            EVENTS + event.id() + "/delete", Map.of(),
                            recurring ? "Die ganze Serie „" + event.title() + "“ löschen?"
                                    : "„" + event.title() + "“ löschen?", null, true));
                }
            }
            case CalendarItem.Todo todo -> {
                if (todo.actions().contains(ItemAction.COMPLETE)) {
                    result.add(new EntryAction("complete", "Abhaken", TODOS + todo.id() + "/status",
                            Map.of("status", "DONE"), null, null, false));
                }
                addEdit(result, todo);
            }
            case CalendarItem.School ignored -> {
            }
        }
        return List.copyOf(result);
    }

    /** The same without the entry to open the dialog, for the bar inside the dialog. */
    public static List<EntryAction> inDialog(CalendarItem item, Instant occurrence) {
        return of(item, occurrence).stream().filter(action -> !EDIT.equals(action.key())).toList();
    }

    private static void addEdit(List<EntryAction> result, CalendarItem item) {
        if (item.actions().contains(ItemAction.EDIT) || item.actions().contains(ItemAction.MOVE)) {
            result.add(edit());
        }
    }
}
