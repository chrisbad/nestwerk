package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
public class HomeController {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN);
    private static final int TILE_LIMIT = 5;

    /** Eine Kennzahl als Ring auf der Startseite; {@code percent} füllt den Ring, {@code tone} wählt den Verlauf. */
    public record Ring(String label, String detail, int done, int total, int percent, String tone) {
        static Ring of(String label, String detail, int done, int total, String tone) {
            return new Ring(label, detail, done, total, total == 0 ? 100 : Math.min(100, Math.round(done * 100f / total)), tone);
        }
    }

    private final CurrentUser currentUser;
    private final TodoAccess todoAccess;
    private final TodoQuery todoQuery;
    private final TodoViews todoViews;
    private final ContactService contactService;
    private final club.badernet.nestwerk.household.HouseholdAccess householdAccess;
    private final club.badernet.nestwerk.household.ChoreTaskService choreTasks;
    private final HouseholdViews householdViews;
    private final club.badernet.nestwerk.backup.BackupService backups;
    private final club.badernet.nestwerk.member.FamilyMemberRepository members;
    private final club.badernet.nestwerk.blocker.RestGoals restGoals;
    private final club.badernet.nestwerk.school.SchoolToday schoolToday;
    private final club.badernet.nestwerk.school.SchoolAccess schoolAccess;

    public HomeController(CurrentUser currentUser, TodoAccess todoAccess, TodoQuery todoQuery, TodoViews todoViews,
                          ContactService contactService,
                          club.badernet.nestwerk.household.HouseholdAccess householdAccess,
                          club.badernet.nestwerk.household.ChoreTaskService choreTasks,
                          HouseholdViews householdViews,
                          club.badernet.nestwerk.backup.BackupService backups,
                          club.badernet.nestwerk.member.FamilyMemberRepository members,
                          club.badernet.nestwerk.blocker.RestGoals restGoals,
                          club.badernet.nestwerk.school.SchoolToday schoolToday,
                          club.badernet.nestwerk.school.SchoolAccess schoolAccess) {
        this.schoolToday = schoolToday;
        this.schoolAccess = schoolAccess;
        this.backups = backups;
        this.members = members;
        this.restGoals = restGoals;
        this.householdAccess = householdAccess;
        this.choreTasks = choreTasks;
        this.householdViews = householdViews;
        this.currentUser = currentUser;
        this.todoAccess = todoAccess;
        this.todoQuery = todoQuery;
        this.todoViews = todoViews;
        this.contactService = contactService;
    }

    @GetMapping("/")
    String index(Model model) {
        LocalDate today = todoQuery.today();
        model.addAttribute("today", today);
        model.addAttribute("todayLabel", today.format(DAY_LABEL));
        Optional<User> me = currentUser.find();
        model.addAttribute("backupWarning", me.flatMap(backups::staleWarning).orElse(null));
        boolean todos = me.map(u -> todoAccess.mayOpen(u.getUsername())).orElse(false);
        model.addAttribute("showTodos", todos);
        model.addAttribute("canCreateTodo", todos && todoAccess.canCreate(me.get()));
        model.addAttribute("showContacts", me.map(u -> contactService.mayOpen(u.getUsername())).orElse(false));
        List<TodoViews.TodoRow> myTodos = List.of();
        if (todos) {
            User user = me.get();
            TodoViews.Labels labels = todoViews.labels(user);
            myTodos = todoQuery.mine(user, TILE_LIMIT).stream()
                    .map(t -> todoViews.row(t, user, labels, today)).toList();
        }
        model.addAttribute("myTodos", myTodos);
        List<Ring> rings = new java.util.ArrayList<>();
        if (todos) {
            User user = me.get();
            List<club.badernet.nestwerk.todo.Todo> urgent = todoQuery.mine(user, Integer.MAX_VALUE);
            long overdue = urgent.stream()
                    .filter(t -> club.badernet.nestwerk.todo.TodoTiming.of(t, today)
                            == club.badernet.nestwerk.todo.TodoTiming.OVERDUE).count();
            int onTime = urgent.size() - (int) overdue;
            rings.add(Ring.of("ToDos im Plan", overdue == 0 ? "nichts überfällig" : overdue + " überfällig",
                    onTime, urgent.size(), overdue == 0 ? "cool" : "warm"));
        }
        boolean household = me.map(u -> householdAccess.mayOpen(u.getUsername())).orElse(false);
        model.addAttribute("showHousehold", household);
        List<HouseholdViews.TaskChip> myChores = List.of();
        if (household) {
            User user = me.get();
            HouseholdViews.Context ctx = householdViews.context(user);
            myChores = householdViews.chips(choreTasks.mine(user, ctx.today()).stream().limit(TILE_LIMIT).toList(), ctx);
        }
        model.addAttribute("myChores", myChores);
        boolean school = me.map(u -> schoolAccess.has(u, club.badernet.nestwerk.school.SchoolPermission.SCHOOL_VIEW))
                .orElse(false);
        model.addAttribute("showSchool", school);
        model.addAttribute("schoolDays", school ? schoolToday.forDate(today, me.get()) : List.of());
        if (household) {
            User user = me.get();
            HouseholdViews.Context ctx = householdViews.context(user);
            if (ctx.ownMember() != null) {
                LocalDate monday = ctx.today().with(java.time.temporal.TemporalAdjusters
                        .previousOrSame(java.time.DayOfWeek.MONDAY));
                List<club.badernet.nestwerk.household.ChoreTask> week = choreTasks
                        .between(user, monday, monday.plusDays(6)).stream()
                        .filter(t -> ctx.ownMember().equals(t.getAssigneeMemberId())).toList();
                List<club.badernet.nestwerk.household.ChoreTask> day = week.stream()
                        .filter(t -> t.getDueOn().equals(ctx.today())).toList();
                rings.add(Ring.of("Haushalt heute", "erledigt", (int) day.stream().filter(t -> !t.isOpen()).count(),
                        day.size(), "fresh"));
                rings.add(Ring.of("Diese Woche", "Hausarbeiten erledigt",
                        (int) week.stream().filter(t -> !t.isOpen()).count(), week.size(), "violet"));
            }
        }
        me.flatMap(u -> members.findByUserId(u.getId())).ifPresent(member ->
                restGoals.status(member.getId(), today).stream().findFirst().ifPresent(goal -> {
                    boolean hours = goal.goal().getKind() == club.badernet.nestwerk.blocker.RestGoal.Kind.HOURS;
                    rings.add(Ring.of("Ruhe diese Woche", goal.goal().getLabel(),
                            hours ? goal.reached() / 60 : goal.reached(), hours ? goal.target() / 60 : goal.target(),
                            goal.met() ? "fresh" : "warm"));
                }));
        model.addAttribute("rings", rings);
        return "index";
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
