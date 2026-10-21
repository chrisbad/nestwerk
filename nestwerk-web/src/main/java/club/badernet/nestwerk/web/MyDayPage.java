package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.kitchen.MealEater;
import club.badernet.nestwerk.kitchen.MealPlanService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Collects what concerns the viewer on one day: own events, own chores, meals they eat and own due to-dos. */
@Component
public class MyDayPage {

    private final CalendarPageData pageData;
    private final FamilyMemberService members;
    private final TodoQuery todoQuery;
    private final TodoAccess todoAccess;
    private final ChoreTaskService choreTasks;
    private final HouseholdAccess householdAccess;
    private final MealPlanService mealPlan;

    public MyDayPage(CalendarPageData pageData, FamilyMemberService members, TodoQuery todoQuery,
                     TodoAccess todoAccess, ChoreTaskService choreTasks, HouseholdAccess householdAccess, MealPlanService mealPlan) {
        this.pageData = pageData;
        this.members = members;
        this.todoQuery = todoQuery;
        this.todoAccess = todoAccess;
        this.choreTasks = choreTasks;
        this.householdAccess = householdAccess;
        this.mealPlan = mealPlan;
    }

    public MyDay forDay(LocalDate day, User viewer) {
        Optional<Long> ownMember = members.findByUserId(viewer.getId()).map(FamilyMember::getId);
        boolean isToday = day.equals(pageData.today());
        List<MyDayEntry> entries = new ArrayList<>();
        List<MyDayEntry> overdue = new ArrayList<>();

        pageData.occurrences(List.of(day), viewer).getOrDefault(day, List.of()).stream()
                .filter(occurrence -> concerns(occurrence, ownMember, viewer))
                .forEach(occurrence -> entries.add(new MyDayEntry.EventEntry(occurrence)));

        if (todoAccess.mayOpen(viewer.getUsername())) {
            for (Todo todo : myTodos(viewer)) {
                if (todo.getDueOn().equals(day)) {
                    entries.add(new MyDayEntry.TodoEntry(todo));
                } else if (isToday && todo.getDueOn().isBefore(day)) {
                    overdue.add(new MyDayEntry.TodoEntry(todo));
                }
            }
        }
        if (householdAccess.mayOpen(viewer.getUsername())) {
            for (ChoreTask task : myChores(day, isToday, ownMember, viewer)) {
                (task.getDueOn().isBefore(day) ? overdue : entries).add(new MyDayEntry.ChoreEntry(task));
            }
        }
        ownMember.ifPresent(own -> myMeals(day, own, viewer).forEach(meal -> entries.add(new MyDayEntry.MealEntry(meal))));
        entries.sort(Comparator.comparingInt(MyDayEntry::order));
        return new MyDay(List.copyOf(entries), List.copyOf(overdue), ownMember.isPresent());
    }

    /** Meals of the day the viewer's member takes part in; empty without the right to see the meal plan. */
    private List<Meal> myMeals(LocalDate day, Long own, User viewer) {
        try {
            return mealPlan.week(day, viewer).meals().stream()
                    .filter(meal -> meal.getDay().equals(day))
                    .filter(meal -> meal.getEaters().stream().map(MealEater::getMemberId).anyMatch(own::equals))
                    .toList();
        } catch (AccessDeniedException e) {
            return List.of();
        }
    }

    /** Events of the viewer's member, or events nobody is assigned to that the viewer created. */
    private static boolean concerns(Occurrence occurrence, Optional<Long> ownMember, User viewer) {
        if (occurrence.memberIds().isEmpty()) {
            return viewer.getId().equals(occurrence.createdBy());
        }
        return ownMember.map(occurrence.memberIds()::contains).orElse(false);
    }

    private List<Todo> myTodos(User viewer) {
        return todoQuery.list(viewer, TodoFilter.defaults().withInvolvement(TodoFilter.Involvement.MINE)).stream()
                .flatMap(group -> group.todos().stream())
                .filter(todo -> todo.getDueOn() != null)
                .toList();
    }

    /** Today includes the overdue open chores of the viewer; other days only chores assigned to the viewer. */
    private List<ChoreTask> myChores(LocalDate day, boolean isToday, Optional<Long> ownMember, User viewer) {
        if (isToday) {
            return choreTasks.mine(viewer, day);
        }
        return ownMember.map(own -> choreTasks.between(viewer, day, day).stream()
                        .filter(ChoreTask::isOpen)
                        .filter(task -> own.equals(task.getAssigneeMemberId()))
                        .toList())
                .orElse(List.of());
    }
}
