package club.badernet.nestwerk.web;

import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.household.HouseholdSettings;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.update.UpdateCheck;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Register of all settings of the app: one place to find, filter and open them. */
@Component("settings")
public class Settings {

    private static final Duration BACKUP_STALE_AFTER = Duration.ofDays(7);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Comparator<SettingEntry> BY_TITLE = Comparator.comparing(SettingEntry::title,
            java.text.Collator.getInstance(java.util.Locale.GERMAN));

    private final List<SettingDescriptor> descriptors;
    private final PermissionCatalog permissions;
    private final EventCategoryService categories;
    private final FamilyMemberService members;
    private final UserService users;
    private final RoleService roles;
    private final BackupService backups;
    private final UpdateCheck updates;
    private final HouseholdSettings householdSettings;
    private final Clock clock;

    public Settings(HouseholdAccess householdAccess, PermissionCatalog permissions, EventCategoryService categories,
                    FamilyMemberService members, UserService users, RoleService roles, BackupService backups,
                    UpdateCheck updates, HouseholdSettings householdSettings, Clock clock) {
        this.permissions = permissions;
        this.categories = categories;
        this.members = members;
        this.users = users;
        this.roles = roles;
        this.backups = backups;
        this.updates = updates;
        this.householdSettings = householdSettings;
        this.clock = clock;
        Predicate<User> household = user -> householdAccess.mayOpen(user.getUsername());
        this.descriptors = List.of(
                setting("calendar.categories", SettingArea.CALENDAR, SettingScope.FAMILY, "Terminkategorien",
                        "Arten von Terminen mit eigener Farbe.", Set.of("Farbe", "Kategorie"),
                        "/calendar/categories", authority("CALENDAR_CATEGORY_MANAGE"),
                        this::categorySummary),
                setting("calendar.times", SettingArea.CALENDAR, SettingScope.FAMILY, "Zeiten und Ruhe-Ziele",
                        "Arbeits-, Schul- und Ruhezeiten, Ruhe-Ziele und das Bundesland für Feiertage.",
                        Set.of("Blocker", "Arbeit", "Ruhe", "Feiertage", "Bundesland"), "/times",
                        authority("CALENDAR_VIEW"), user -> SettingValue.NONE),
                setting("calendar.import", SettingArea.CALENDAR, SettingScope.FAMILY, "Kalender-Import",
                        "Termine aus einer iCal-Datei übernehmen.", Set.of("iCal", "ics", "Import"),
                        "/import/calendar", authority("CALENDAR_EVENT_CREATE"),
                        user -> SettingValue.NONE),
                setting("household.chores", SettingArea.HOUSEHOLD, SettingScope.FAMILY, "Hausarbeiten",
                        "Wiederkehrende Hausarbeiten, ihre Zuständigen und Termine.", Set.of("Aufgaben", "Raum"),
                        "/household/chores", household, user -> SettingValue.NONE),
                setting("household.rewards", SettingArea.HOUSEHOLD, SettingScope.FAMILY, "Belohnungen",
                        "Belohnungen, die man mit Haushaltspunkten einlösen kann.", Set.of("Punkte", "Konto"),
                        "/household/rewards", household, user -> SettingValue.NONE),
                setting("household.personDay", SettingArea.HOUSEHOLD, SettingScope.FAMILY, "Personentag",
                        "Stunden pro Personentag für die Auswertung des Haushaltsplans.",
                        Set.of("Personentage", "PT", "Stunden", "Auswertung"), "/household/settings", household,
                        user -> SettingValue.ok(HouseholdSettings.plain(householdSettings.hoursPerPersonDay())
                                + " Stunden pro Personentag")),
                setting("household.tablets", SettingArea.HOUSEHOLD, SettingScope.SYSTEM, "Wand-Tablets",
                        "Tablets, die den Haushaltsplan an der Wand zeigen.", Set.of("Gerät", "Kopplung"),
                        "/admin/devices", authority("HOUSEHOLD_DEVICE_MANAGE"),
                        user -> SettingValue.NONE),
                setting("kitchen.settings", SettingArea.KITCHEN, SettingScope.FAMILY, "Küche",
                        "Mahlzeiten, Kalorienziele und die Verbindung zu Bring!.", Set.of("Bring", "Kalorien",
                                "Mahlzeit", "Einkauf"), "/kitchen/settings",
                        authority("KITCHEN_SETTINGS"), user -> SettingValue.NONE),
                setting("school.school", SettingArea.SCHOOL, SettingScope.FAMILY, "Schulen und Klassen",
                        "Schulen der Kinder und ihre Klassen.", Set.of("Klasse", "Kind"), "/school/settings/school",
                        authority("SCHOOL_VIEW"), user -> SettingValue.NONE),
                setting("school.years", SettingArea.SCHOOL, SettingScope.FAMILY, "Schuljahre",
                        "Schuljahre und Halbjahre einrichten.", Set.of("Halbjahr", "Zeugnis"),
                        "/school/settings/years", authority("SCHOOL_VIEW"), user -> SettingValue.NONE),
                setting("school.subjects", SettingArea.SCHOOL, SettingScope.FAMILY, "Fächer",
                        "Fächer und ihre Lehrkräfte.", Set.of("Lehrer", "Fach"), "/school/settings/subjects",
                        authority("SCHOOL_VIEW"), user -> SettingValue.NONE),
                setting("school.lessons", SettingArea.SCHOOL, SettingScope.FAMILY, "Stundenplan",
                        "Unterrichtsstunden und das Zeitraster.", Set.of("Stunden", "Raster", "Pause"),
                        "/school/settings/lessons", authority("SCHOOL_VIEW"), user -> SettingValue.NONE),
                setting("school.holidays", SettingArea.SCHOOL, SettingScope.FAMILY, "Ferien",
                        "Ferien und schulfreie Tage.", Set.of("Urlaub", "frei"), "/school/holidays",
                        authority("SCHOOL_VIEW"), user -> SettingValue.NONE),
                setting("notifications.mine", SettingArea.NOTIFICATIONS, SettingScope.PERSONAL,
                        "Meine Benachrichtigungswege", "Wie und wann du benachrichtigt wirst.",
                        Set.of("Push", "Mail", "E-Mail", "Erinnerung"), "/settings/notifications",
                        user -> true, user -> SettingValue.NONE),
                setting("notifications.family", SettingArea.NOTIFICATIONS, SettingScope.FAMILY,
                        "Benachrichtigungen der Familie", "Standardwege und Zeitplan der Erinnerungen für alle.",
                        Set.of("Push", "Mail", "Erinnerung", "Standard"), "/admin/notifications",
                        authority("NOTIFICATION_SETTINGS_MANAGE"), user -> SettingValue.NONE),
                setting("account.pin", SettingArea.ACCOUNT, SettingScope.PERSONAL, "PIN fürs Tablet",
                        "Deine PIN, mit der du dich am Wand-Tablet anmeldest.", Set.of("Tablet", "Anmeldung"),
                        "/settings/pin", household, user -> SettingValue.NONE),
                setting("account.ai", SettingArea.ACCOUNT, SettingScope.PERSONAL, "KI-Zugänge",
                        "Zugänge für KI-Assistenten, die in deinem Namen arbeiten.", Set.of("MCP", "Token", "Assistent"),
                        "/settings/ai", authority("AI_ACCESS"), user -> SettingValue.NONE),
                setting("system.users", SettingArea.SYSTEM, SettingScope.SYSTEM, "Benutzer",
                        "Konten der Familie, ihre Rollen und Passwörter.", Set.of("Konto", "Passwort", "Anmeldung"),
                        "/admin/users", authority("USER_MANAGE"), this::userSummary),
                setting("system.roles", SettingArea.SYSTEM, SettingScope.SYSTEM, "Rollen",
                        "Rollen mit ihren Rechten.", Set.of("Rechte", "Berechtigung"), "/admin/roles",
                        authority("ACCESS_ROLE_MANAGE"), this::roleSummary),
                setting("system.permissions", SettingArea.SYSTEM, SettingScope.SYSTEM, "Rechtetexte",
                        "Erklärungen zu den einzelnen Rechten.", Set.of("Rechte", "Berechtigung", "Beschreibung"),
                        "/admin/permissions", authority("ACCESS_ROLE_MANAGE"),
                        user -> SettingValue.NONE),
                setting("system.members", SettingArea.SYSTEM, SettingScope.SYSTEM, "Mitglieder",
                        "Familienmitglieder mit Farbe und Konto.", Set.of("Person", "Farbe", "Familie"),
                        "/admin/members", authority("MEMBER_MANAGE"), this::memberSummary),
                setting("system.backup", SettingArea.SYSTEM, SettingScope.SYSTEM, "Sicherungen",
                        "Sicherungen anlegen, Zeitplan einrichten und wiederherstellen.", Set.of("Backup", "Wiederherstellen",
                                "Zeitplan"), "/admin/backup", authority("BACKUP_MANAGE"),
                        this::backupSummary),
                setting("system.updates", SettingArea.SYSTEM, SettingScope.SYSTEM, "Updates",
                        "Neue Versionen suchen und einspielen.", Set.of("Version", "Release", "Aktualisierung"),
                        "/admin/updates", authority("UPDATE_MANAGE"), this::updateSummary));
    }

    private Predicate<User> authority(String authority) {
        return user -> permissions.effective(user).contains(authority);
    }

    private static SettingDescriptor setting(String id, SettingArea area, SettingScope scope, String title,
                                             String description, Set<String> keywords, String path,
                                             Predicate<User> visible,
                                             Function<User, SettingValue> value) {
        return new SettingDescriptor(id, area, scope, title, description, keywords, path, visible, value);
    }

    /** The settings the user may see and that match the filter, ordered by title. */
    public List<SettingEntry> list(User viewer, SettingFilter filter) {
        return descriptors.stream()
                .filter(d -> d.visible().test(viewer))
                .filter(d -> filter.areas().isEmpty() || filter.areas().contains(d.area()))
                .filter(d -> filter.scope() == null || filter.scope() == d.scope())
                .filter(d -> filter.text().isEmpty() || d.matches(filter.text()))
                .map(d -> new SettingEntry(d, valueOf(d, viewer)))
                .filter(e -> filter.status() == null || filter.status() == e.status())
                .sorted(BY_TITLE)
                .toList();
    }

    /** Number of visible settings that need attention, for hints elsewhere in the app. */
    public long attentionCount(User viewer) {
        return list(viewer, new SettingFilter("", Set.of(), null, SettingStatus.ATTENTION)).size();
    }

    private SettingValue valueOf(SettingDescriptor descriptor, User viewer) {
        try {
            return descriptor.value().apply(viewer);
        } catch (AccessDeniedException e) {
            return SettingValue.NONE;
        }
    }

    private SettingValue categorySummary(User viewer) {
        int count = categories.list(viewer).size();
        return SettingValue.ok(count == 1 ? "1 Kategorie" : count + " Kategorien");
    }

    private SettingValue memberSummary(User viewer) {
        int count = members.findAll().size();
        return count == 0 ? SettingValue.notSet("noch keine Mitglieder")
                : SettingValue.ok(count == 1 ? "1 Mitglied" : count + " Mitglieder");
    }

    private SettingValue userSummary(User viewer) {
        int count = users.findAll().size();
        return SettingValue.ok(count == 1 ? "1 Benutzer" : count + " Benutzer");
    }

    private SettingValue roleSummary(User viewer) {
        int count = roles.findAll().size();
        return SettingValue.ok(count == 1 ? "1 Rolle" : count + " Rollen");
    }

    private SettingValue backupSummary(User viewer) {
        Optional<BackupRun> last = backups.recentRuns(viewer, 1).stream().findFirst();
        if (last.isEmpty()) {
            return SettingValue.notSet("noch keine Sicherung");
        }
        BackupRun run = last.get();
        Instant at = run.getFinishedAt() != null ? run.getFinishedAt() : run.getStartedAt();
        String day = DAY.format(at.atZone(ZoneId.systemDefault()));
        if (run.getStatus() == BackupRun.Status.FAILED) {
            return SettingValue.attention("letzte Sicherung fehlgeschlagen (" + day + ")");
        }
        boolean stale = Duration.between(at, clock.instant()).compareTo(BACKUP_STALE_AFTER) > 0;
        return stale ? SettingValue.attention("letzte Sicherung vom " + day)
                : SettingValue.ok("letzte Sicherung vom " + day);
    }

    private SettingValue updateSummary(User viewer) {
        String version = updates.current().raw();
        return updates.cached().isPresent()
                ? SettingValue.attention("Version " + version + ", ein Update ist verfügbar")
                : SettingValue.ok("Version " + version);
    }
}
