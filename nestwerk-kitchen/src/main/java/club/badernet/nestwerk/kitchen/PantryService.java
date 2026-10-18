package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.blocker.BusyTimes;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Vorrat pflegen und beim Kochen abbuchen. */
@Service
public class PantryService {

    public static final int WARN_DAYS = 2;
    private static final double EPSILON = 1e-6;

    private final PantryRepository pantry;
    private final FoodRepository foods;
    private final FoodMatcher matcher;
    private final MealRepository meals;
    private final RecipeRepository recipes;
    private final KitchenAccess access;
    private final BusyTimes busyTimes;
    private final Clock clock;

    public PantryService(PantryRepository pantry, FoodRepository foods, FoodMatcher matcher, MealRepository meals,
                         RecipeRepository recipes, KitchenAccess access, BusyTimes busyTimes, Clock clock) {
        this.pantry = pantry;
        this.foods = foods;
        this.matcher = matcher;
        this.meals = meals;
        this.recipes = recipes;
        this.access = access;
        this.busyTimes = busyTimes;
        this.clock = clock;
    }

    /** Entnahme aus einem Vorratsposten, Menge in der Einheit des Postens. */
    public record Take(PantryItem item, double amount) {
    }

    /** Was für eine Zutat abgebucht wird; {@code missing} ist der Rest, den der Vorrat nicht deckt. */
    public record Deduction(RecipeIngredient ingredient, double needed, List<Take> takes, double missing,
                            String note) {

        public boolean hasStock() {
            return !takes.isEmpty();
        }
    }

    /** Vorschau auf „Gekocht“: Faktor gegenüber dem Rezept und die Abbuchungen je Zutat. */
    public record CookPreview(Meal meal, double factor, List<Deduction> deductions) {
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(busyTimes.zone()));
    }

    @Transactional(readOnly = true)
    public List<PantryItem> list(User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return pantry.findAll().stream().sorted(PantryService::byExpiry).toList();
    }

    @Transactional(readOnly = true)
    public PantryItem get(Long id, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return find(id);
    }

    @Transactional
    public PantryItem add(String name, double amount, Unit unit, String location, LocalDate bestBefore, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 100) {
            throw new IllegalArgumentException("Bitte einen Namen angeben (höchstens 100 Zeichen).");
        }
        checkAmount(amount);
        Food food = matcher.match(n).orElse(null);
        return save(food, food != null && food.getName().equalsIgnoreCase(n) ? food.getName() : n, amount, unit,
                location, bestBefore);
    }

    /** Legt einen Posten zu einem bekannten Lebensmittel an; ohne MHD gilt heute + Haltbarkeit des Lebensmittels. */
    @Transactional
    public PantryItem add(Food food, double amount, Unit unit, String location, LocalDate bestBefore, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        checkAmount(amount);
        return save(food, food.getName(), amount, unit, location, bestBefore);
    }

    private PantryItem save(Food food, String name, double amount, Unit unit, String location, LocalDate bestBefore) {
        LocalDate until = bestBefore;
        if (until == null && food != null && food.getShelfDays() != null) {
            until = today().plusDays(food.getShelfDays());
        }
        String place = location == null || location.isBlank() ? null : location.strip();
        if (place != null && place.length() > 50) {
            throw new IllegalArgumentException("Der Ort ist zu lang (höchstens 50 Zeichen).");
        }
        return pantry.save(new PantryItem(food, name, amount, unit == null ? Unit.PIECE : unit, place, until));
    }

    @Transactional
    public void change(Long id, double amount, Unit unit, String location, LocalDate bestBefore, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        checkAmount(amount);
        String place = location == null || location.isBlank() ? null : location.strip();
        find(id).change(amount, unit == null ? Unit.PIECE : unit, place, bestBefore);
    }

    /** Entnimmt eine Menge; ist nichts mehr da, verschwindet der Posten. */
    @Transactional
    public void take(Long id, double amount, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        checkAmount(amount);
        PantryItem item = find(id);
        item.setAmount(item.getAmount() - amount);
        if (item.getAmount() <= EPSILON) {
            pantry.delete(item);
        }
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        pantry.delete(find(id));
    }

    /** Rezepte, die Lebensmittel enthalten, die bald ablaufen – die mit den meisten Treffern zuerst. */
    @Transactional(readOnly = true)
    public List<Recipe> useUp(User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        Set<Long> expiring = pantry.findByBestBeforeLessThanEqual(today().plusDays(MealPlanService.EXPIRY_HORIZON_DAYS))
                .stream().map(PantryItem::getFood).filter(Objects::nonNull).map(Food::getId)
                .collect(Collectors.toSet());
        if (expiring.isEmpty()) {
            return List.of();
        }
        Map<Recipe, Long> hits = new HashMap<>();
        for (Recipe r : recipes.usingFoods(expiring)) {
            hits.put(r, r.getIngredients().stream().map(RecipeIngredient::getFood).filter(Objects::nonNull)
                    .map(Food::getId).filter(expiring::contains).distinct().count());
        }
        return hits.entrySet().stream()
                .sorted(Map.Entry.<Recipe, Long>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().getTitle()))
                .map(Map.Entry::getKey).toList();
    }

    // --- Gekocht ---------------------------------------------------------------------

    /**
     * Berechnet, was beim Kochen abgebucht würde. {@code overrides} korrigiert die Menge einer Zutat (Schlüssel: ID der
     * Zutat, Menge in deren Einheit); 0 bucht nichts ab. Es wird nichts verändert.
     */
    @Transactional(readOnly = true)
    public CookPreview preview(Long mealId, Map<Long, Double> overrides, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return compute(meal(mealId), overrides);
    }

    /** Bucht ab (ältestes MHD zuerst, nie unter 0, leere Posten verschwinden) und setzt „gekocht am“. */
    @Transactional
    public CookPreview cook(Long mealId, Map<Long, Double> overrides, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_PLAN);
        Meal meal = meal(mealId);
        if (meal.isCooked()) {
            throw new IllegalArgumentException("Diese Mahlzeit ist schon als gekocht eingetragen.");
        }
        CookPreview preview = compute(meal, overrides);
        for (Deduction d : preview.deductions()) {
            for (Take t : d.takes()) {
                PantryItem item = t.item();
                item.setAmount(Math.max(0, item.getAmount() - t.amount()));
                if (item.getAmount() <= EPSILON) {
                    pantry.delete(item);
                }
            }
        }
        meal.setCookedAt(Instant.now(clock));
        return preview;
    }

    private CookPreview compute(Meal meal, Map<Long, Double> overrides) {
        Recipe recipe = meal.getRecipe();
        if (recipe == null) {
            return new CookPreview(meal, 1, List.of());
        }
        double factor = meal.totalPortions() / Math.max(1, recipe.getServings());
        Map<Long, Double> left = new HashMap<>();
        List<Deduction> result = new ArrayList<>();
        for (RecipeIngredient ing : recipe.getIngredients()) {
            if (ing.getAmount() == null) {
                continue;
            }
            double needed = overrides != null && overrides.containsKey(ing.getId())
                    ? overrides.get(ing.getId()) : ing.getAmount() * factor;
            if (needed < 0) {
                throw new IllegalArgumentException("Mengen dürfen nicht negativ sein.");
            }
            result.add(deduct(ing, needed, left));
        }
        return new CookPreview(meal, factor, result);
    }

    private Deduction deduct(RecipeIngredient ing, double needed, Map<Long, Double> left) {
        Food food = ing.getFood();
        if (needed <= EPSILON) {
            return new Deduction(ing, 0, List.of(), 0, "nichts abbuchen");
        }
        if (food == null) {
            return new Deduction(ing, needed, List.of(), needed, "ohne Lebensmittel – kein Abzug");
        }
        List<PantryItem> stock = pantry.findByFoodId(food.getId()).stream().sorted(PantryService::byExpiry).toList();
        List<Take> takes = new ArrayList<>();
        double remaining = needed;
        boolean incompatible = false;
        for (PantryItem item : stock) {
            if (remaining <= EPSILON) {
                break;
            }
            double available = left.getOrDefault(item.getId(), item.getAmount());
            if (available <= EPSILON) {
                continue;
            }
            var wanted = Quantities.convert(remaining, ing.getUnit(), item.getUnit(), food);
            if (wanted.isEmpty()) {
                incompatible = true;
                continue;
            }
            double take = Math.min(available, wanted.get());
            takes.add(new Take(item, take));
            left.put(item.getId(), available - take);
            if (take >= wanted.get() - EPSILON) {
                remaining = 0;
            } else {
                remaining -= Quantities.convert(take, item.getUnit(), ing.getUnit(), food).orElse(remaining);
            }
        }
        double missing = remaining <= EPSILON ? 0 : remaining;
        String note = null;
        if (takes.isEmpty()) {
            note = incompatible ? "Vorrat in anderer Einheit ohne Umrechnung – kein Abzug" : "nicht im Vorrat";
        } else if (missing > 0) {
            note = "Vorrat reicht nicht";
        }
        return new Deduction(ing, needed, List.copyOf(takes), missing, note);
    }

    private Meal meal(Long id) {
        Meal meal = meals.findById(id).orElseThrow(() -> new NoSuchElementException("Mahlzeit nicht gefunden"));
        if (meal.getRecipe() != null) {
            meal.getRecipe().getIngredients().forEach(i -> {
                if (i.getFood() != null) {
                    i.getFood().getName();
                }
            });
        }
        return meal;
    }

    private PantryItem find(Long id) {
        return pantry.findById(id).orElseThrow(() -> new NoSuchElementException("Vorratsposten nicht gefunden"));
    }

    private static void checkAmount(double amount) {
        if (!(amount > 0) || amount > 100_000) {
            throw new IllegalArgumentException("Die Menge muss größer als 0 sein.");
        }
    }

    /** Ältestes MHD zuerst, Posten ohne MHD zuletzt, dann der früher hinzugefügte. */
    static int byExpiry(PantryItem a, PantryItem b) {
        return Comparator.comparing(PantryItem::getBestBefore, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(PantryItem::getAddedAt).thenComparing(PantryItem::getId, Comparator.nullsLast(Long::compare))
                .compare(a, b);
    }
}
