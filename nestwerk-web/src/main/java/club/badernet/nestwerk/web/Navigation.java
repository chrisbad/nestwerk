package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.todo.TodoAccess;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Registry of all navigation targets with the rule who may see them. */
@Component("navigation")
public class Navigation {

    private final List<NavigationItem> items;
    private final List<QuickAction> quickActions;

    public Navigation(TodoAccess todoAccess, HouseholdAccess householdAccess, ContactService contacts) {
        Predicate<Authentication> everyone = Visibility.everyone();
        Predicate<Authentication> todos = authentication -> todoAccess.mayOpen(authentication.getName());
        Predicate<Authentication> household = authentication -> householdAccess.mayOpen(authentication.getName());
        Predicate<Authentication> contactBook = authentication -> contacts.mayOpen(authentication.getName());
        items = List.of(
                new NavigationItem("home", "Start", "/", NavigationGroup.AREA, "home", true, everyone),
                new NavigationItem("calendar", "Kalender", "/calendar", NavigationGroup.AREA, "calendar", true,
                        Visibility.hasAuthority("CALENDAR_VIEW")),
                new NavigationItem("times", "Zeiten", "/times", NavigationGroup.AREA, "times", false,
                        Visibility.hasAuthority("CALENDAR_VIEW")),
                new NavigationItem("todos", "ToDos", "/todos", NavigationGroup.AREA, "todos", true, todos),
                new NavigationItem("household", "Haushalt", "/household", NavigationGroup.AREA, "household", true,
                        household),
                new NavigationItem("kitchen", "Küche", "/kitchen", NavigationGroup.AREA, "kitchen", false,
                        Visibility.hasAuthority("KITCHEN_VIEW")),
                new NavigationItem("school", "Schule", "/school", NavigationGroup.AREA, "school", false,
                        Visibility.hasAuthority("SCHOOL_VIEW")),
                new NavigationItem("contacts", "Kontakte", "/contacts", NavigationGroup.AREA, "contacts", false,
                        contactBook),
                new NavigationItem("manual", "Handbuch", "/manual", NavigationGroup.AREA, "manual", false, everyone),
                new NavigationItem("users", "Benutzer", "/admin/users", NavigationGroup.ADMINISTRATION, "users",
                        false, Visibility.hasAuthority("USER_MANAGE")),
                new NavigationItem("roles", "Rollen", "/admin/roles", NavigationGroup.ADMINISTRATION, "roles", false,
                        Visibility.hasAuthority("ACCESS_ROLE_MANAGE")),
                new NavigationItem("members", "Mitglieder", "/admin/members", NavigationGroup.ADMINISTRATION,
                        "members", false, Visibility.hasAuthority("MEMBER_MANAGE")),
                new NavigationItem("settings", "Einstellungen", "/settings", NavigationGroup.ACCOUNT, null, false,
                        everyone),
                new NavigationItem("notifications", "Benachrichtigungen", "/notifications", NavigationGroup.ACCOUNT,
                        null, false, everyone),
                new NavigationItem("my-notification-channels", "Meine Benachrichtigungswege",
                        "/settings/notifications", NavigationGroup.ACCOUNT, null, false, everyone),
                new NavigationItem("pin", "PIN fürs Tablet", "/settings/pin", NavigationGroup.ACCOUNT, null, false,
                        household),
                new NavigationItem("ai", "KI-Zugänge", "/settings/ai", NavigationGroup.ACCOUNT, null, false,
                        Visibility.hasAuthority("AI_ACCESS")),
                new NavigationItem("notification-settings", "Benachrichtigungen einstellen", "/admin/notifications",
                        NavigationGroup.ACCOUNT, null, false, Visibility.hasAuthority("NOTIFICATION_SETTINGS_MANAGE")),
                new NavigationItem("devices", "Wand-Tablets", "/admin/devices", NavigationGroup.ACCOUNT, null, false,
                        Visibility.hasAuthority("HOUSEHOLD_DEVICE_MANAGE")),
                new NavigationItem("backup", "Sicherungen", "/admin/backup", NavigationGroup.ACCOUNT, null, false,
                        Visibility.hasAuthority("BACKUP_MANAGE")),
                new NavigationItem("updates", "Updates", "/admin/updates", NavigationGroup.ACCOUNT, null, false,
                        Visibility.hasAuthority("UPDATE_MANAGE")));
        quickActions = List.of(
                new QuickAction("event", "Termin", "/calendar/events/new",
                        Visibility.hasAuthority("CALENDAR_EVENT_CREATE")),
                new QuickAction("todo", "ToDo", "/todos/new", todos),
                new QuickAction("time", "Zeit (Arbeit, Schule, Ruhe)", "/times/new",
                        Visibility.hasAuthority("CALENDAR_VIEW")),
                new QuickAction("shopping", "Einkaufsartikel", "/kitchen/shopping",
                        Visibility.hasAuthority("KITCHEN_SHOPPING")));
    }

    /** The entries of a group that the user may see, in display order. */
    public List<NavigationItem> visible(Authentication authentication, NavigationGroup group) {
        return items.stream()
                .filter(item -> item.group() == group && item.isVisibleTo(authentication))
                .toList();
    }

    /** What the user may create from anywhere in the app. */
    public List<QuickAction> quickActions(Authentication authentication) {
        return quickActions.stream().filter(action -> action.visible().test(authentication)).toList();
    }

    /** Entries of the tab bar on phones. */
    public List<NavigationItem> tabs(Authentication authentication) {
        return visible(authentication, NavigationGroup.AREA).stream().filter(NavigationItem::tab).toList();
    }

    /** The entry whose path is the longest prefix of the given local path, e.g. {@code /todos/5} gives ToDos. */
    public Optional<NavigationItem> forPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String bare = path.split("[?#]", 2)[0];
        return items.stream()
                .filter(item -> item.path().equals("/") ? bare.equals("/")
                        : bare.equals(item.path()) || bare.startsWith(item.path() + "/"))
                .max(Comparator.comparingInt(item -> item.path().length()));
    }
}
