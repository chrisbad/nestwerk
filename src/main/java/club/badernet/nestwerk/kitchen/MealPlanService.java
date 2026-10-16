package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.blocker.BlockerSlot;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BusyTimes;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Essensplan: Mahlzeiten anlegen, verschieben, Mitesser, Woche auffüllen. */
@Service
public class MealPlanService {

    static final int RECENT_DAYS = 14;
    static final int EXPIRY_HORIZON_DAYS = 5;
    static final LocalTime BUSY_FROM = LocalTime.of(16, 0);
    static final LocalTime BUSY_TO = LocalTime.of(20, 0);

    /** Eine Woche Montag bis Sonntag mit allen Mahlzeiten. */
    public record Week(LocalDate monday, List<LocalDate> days, List<MealSlot> slots, List<Meal> meals,
                       List<FamilyMember> members) {
    }

    private final MealRepository meals;
    private final MealSlotRepository slots;
    private final RecipeRepository recipes;
    private final FamilyMemberRepository members;
    private final PantryRepository pantry;
    private final KitchenAccess access;
    private final BusyTimes busyTimes;
    private final Clock clock;
    private final Random random = new Random();

    public MealPlanService(MealRepository meals, MealSlotRepository slots, RecipeRepository recipes,
                           FamilyMemberRepository members, PantryRepository pantry, KitchenAccess access,
                           BusyTimes busyTimes, Clock clock) {
        this.meals = meals;
        this.slots = slots;
        this.recipes = recipes;
        this.members = members;
        this.pantry = pantry;
        this.access = access;
        this.busyTimes = busyTimes;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(busyTimes.zone()));
    }

    public static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    @Transactional(readOnly = true)
    public Week week(LocalDate anyDay, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        LocalDate monday = monday(anyDay);
        List<LocalDate> days = monday.datesUntil(monday.plusDays(7)).toList();
        List<Meal> list = meals.findByDayBetweenOrderByDayAsc(monday, monday.plusDays(6));
        list.forEach(m -> {
            if (m.getRecipe() != null) {
                m.getRecipe().getIngredients().forEach(i -> Optional.ofNullable(i.getFood()).map(Food::getName));
            }
        });
        List<FamilyMember> people = members.findAll().stream().sorted(Comparator.comparing(FamilyMember::getName))
                .toList();
        return new Week(monday, days, slots.findAllByOrderBySortOrderAsc(), list, people);
    }

    @Transactional(readOnly = true)
    public Meal get(Long id, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        Meal meal = meals.findById(id).orElseThrow(() -> new NoSuchElementException("Mahlzeit nicht gefunden"));
        if (meal.getRecipe() != null) {
            meal.getRecipe().getIngredients().forEach(i -> Optional.ofNullable(i.getFood()).map(Food::getName));
        }
        return meal;
    }

    /**
     * Legt eine Mahlzeit an: mit Rezept oder freiem Titel (passt der Titel genau zu einem Rezept, wird es verknüpft).
     * Mitesser sind alle Personen mit vorgeschlagener Portion.
     */
    @Transactional
    public Meal add(LocalDate day, Long slotId, Long recipeId, String title, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_PLAN);
        MealSlot slot = slot(slotId);
        Recipe recipe = null;
        if (recipeId != null) {
            recipe = recipes.findById(recipeId).orElseThrow(() -> new NoSuchElementException("Rezept nicht gefunden"));
        } else {
            String t = title == null ? "" : title.strip();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("Bitte ein Rezept wählen oder einen Titel eingeben.");
            }
            recipe = recipes.findAllByOrderByTitleAsc().stream().filter(r -> r.getTitle().equalsIgnoreCase(t))
                    .findFirst().orElse(null);
        }
        String mealTitle = recipe != null ? recipe.getTitle() : title.strip();
        Meal meal = new Meal(day, slot, recipe, mealTitle.length() > 200 ? mealTitle.substring(0, 200) : mealTitle);
        Double kcal = kcalPerServing(recipe);
        for (FamilyMember m : members.findAll()) {
            meal.putEater(m.getId(), PortionAdvisor.suggest(m.getKcalGoal(), slot.getShare(), kcal));
        }
        return meals.save(meal);
    }

    @Transactional
    public Meal move(Long id, LocalDate day, Long slotId, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_PLAN);
        Meal meal = get(id, actor);
        meal.moveTo(day, slot(slotId));
        return meal;
    }

    /** Setzt Mitesser und Portionen; leere Auswahl ist erlaubt (dann zählt die Mahlzeit für niemanden). */
    @Transactional
    public Meal updateEaters(Long id, Map<Long, Double> portions, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_PLAN);
        Meal meal = get(id, actor);
        Set<Long> known = members.findAll().stream().map(FamilyMember::getId).collect(Collectors.toSet());
        new ArrayList<>(meal.getEaters()).forEach(e -> meal.removeEater(e.getMemberId()));
        portions.forEach((member, p) -> {
            if (!known.contains(member)) {
                throw new IllegalArgumentException("Unbekannte Person.");
            }
            if (p == null || p <= 0 || p > 20) {
                throw new IllegalArgumentException("Portionen zwischen 0 und 20.");
            }
            meal.putEater(member, p);
        });
        return meal;
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_PLAN);
        meals.delete(get(id, actor));
    }

    /** Füllt leere, zum Auffüllen markierte Mahlzeiten von heute bis Sonntag der Woche; liefert die Anzahl. */
    @Transactional
    public int autoFill(LocalDate anyDay, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_PLAN);
        LocalDate monday = monday(anyDay);
        LocalDate sunday = monday.plusDays(6);
        LocalDate today = today();
        LocalDate from = monday.isBefore(today) ? today : monday;
        if (from.isAfter(sunday)) {
            return 0;
        }
        List<MealSlot> fillable = slots.findAllByOrderBySortOrderAsc().stream().filter(MealSlot::isAutoFill).toList();
        List<Recipe> all = recipes.findAllByOrderByTitleAsc();
        if (fillable.isEmpty() || all.isEmpty()) {
            return 0;
        }
        LocalDate earliest = today.minusDays(RECENT_DAYS).isBefore(monday) ? today.minusDays(RECENT_DAYS) : monday;
        List<Meal> existing = meals.findByDayBetweenOrderByDayAsc(earliest, sunday);
        Set<Long> avoid = existing.stream().map(Meal::getRecipe).filter(Objects::nonNull).map(Recipe::getId)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> expiring = pantry.findByBestBeforeLessThanEqual(today.plusDays(EXPIRY_HORIZON_DAYS)).stream()
                .map(PantryItem::getFood).filter(Objects::nonNull).map(Food::getId).collect(Collectors.toSet());
        List<FamilyMember> people = members.findAll();
        Set<Long> peopleIds = people.stream().map(FamilyMember::getId).collect(Collectors.toSet());
        int filled = 0;
        for (LocalDate day = from; !day.isAfter(sunday); day = day.plusDays(1)) {
            boolean busy = busy(day, peopleIds);
            for (MealSlot slot : fillable) {
                LocalDate d = day;
                boolean taken = existing.stream().anyMatch(m -> m.getDay().equals(d) && m.getSlot().getId().equals(slot.getId()));
                if (taken) {
                    continue;
                }
                Double target = target(people, slot);
                Optional<Recipe> pick = AutoFill.pick(all, avoid, expiring, busy, target, random);
                if (pick.isEmpty()) {
                    continue;
                }
                Meal meal = add(day, slot.getId(), pick.get().getId(), null, actor);
                existing.add(meal);
                avoid.add(pick.get().getId());
                filled++;
            }
        }
        return filled;
    }

    @Transactional(readOnly = true)
    public double suggestedPortions(Recipe recipe, MealSlot slot, FamilyMember member) {
        return PortionAdvisor.suggest(member.getKcalGoal(), slot.getShare(), kcalPerServing(recipe));
    }

    public static Double kcalPerServing(Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        double kcal = NutritionCalculator.forRecipe(recipe).perServing().kcal();
        return kcal > 0 ? kcal : null;
    }

    private Double target(List<FamilyMember> people, MealSlot slot) {
        List<Integer> goals = people.stream().map(FamilyMember::getKcalGoal).filter(Objects::nonNull).toList();
        if (goals.isEmpty()) {
            return null;
        }
        return goals.stream().mapToInt(Integer::intValue).average().orElse(0) * slot.getShare() / 100.0;
    }

    /** Voll ist ein Tag, wenn jemand zwischen 16 und 20 Uhr Termine oder warnende/sperrende Blocker hat. */
    boolean busy(LocalDate day, Set<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return false;
        }
        ZoneId zone = busyTimes.zone();
        Instant from = day.atTime(BUSY_FROM).atZone(zone).toInstant();
        Instant to = day.atTime(BUSY_TO).atZone(zone).toInstant();
        if (busyTimes.events(memberIds, from, to, null).values().stream().anyMatch(l -> !l.isEmpty())) {
            return true;
        }
        for (Long member : memberIds) {
            for (BlockerSlot s : busyTimes.blockerSlots(member, from, to)) {
                if (s.blocker().getStrength() != BlockerStrength.SHOW && s.overlaps(from, to)) {
                    return true;
                }
            }
        }
        return false;
    }

    private MealSlot slot(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Bitte eine Mahlzeit wählen.");
        }
        return slots.findById(id).orElseThrow(() -> new NoSuchElementException("Mahlzeit nicht gefunden"));
    }
}
