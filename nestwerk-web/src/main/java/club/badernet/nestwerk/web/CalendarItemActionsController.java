package club.badernet.nestwerk.web;

import club.badernet.nestwerk.item.CalendarItems;
import club.badernet.nestwerk.item.ItemType;
import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** What the viewer may do right on a calendar entry; the context menu of the calendar asks for it. */
@RestController
@RequestMapping("/calendar/items")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarItemActionsController {

    private final CalendarItems items;
    private final CurrentUser currentUser;

    public CalendarItemActionsController(CalendarItems items, CurrentUser currentUser) {
        this.items = items;
        this.currentUser = currentUser;
    }

    @GetMapping("/{kind}/{id}/actions")
    List<EntryAction> actions(@PathVariable String kind, @PathVariable Long id,
                              @RequestParam(required = false) Instant occurrence) {
        ItemType type = switch (kind) {
            case "events" -> ItemType.EVENT;
            case "chores" -> ItemType.CHORE;
            case "todos" -> ItemType.TODO;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        return items.find(type, id, currentUser.require()).map(item -> EntryAction.of(item, occurrence))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
