package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/** Einkaufsliste: aus dem Plan erzeugen, freie Artikel, abhaken und einräumen. */
@Service
public class ShoppingListService {

    private static final Set<ShoppingStatus> VISIBLE = EnumSet.of(ShoppingStatus.OPEN, ShoppingStatus.SENT,
            ShoppingStatus.BOUGHT);

    private final ShoppingRepository shopping;
    private final MealRepository meals;
    private final PantryRepository pantryItems;
    private final PantryService pantry;
    private final FoodService foods;
    private final FoodMatcher matcher;
    private final MealPlanService plan;
    private final KitchenAccess access;
    private final Clock clock;

    public ShoppingListService(ShoppingRepository shopping, MealRepository meals, PantryRepository pantryItems,
                               PantryService pantry, FoodService foods, FoodMatcher matcher, MealPlanService plan,
                               KitchenAccess access,
                               Clock clock) {
        this.shopping = shopping;
        this.meals = meals;
        this.pantryItems = pantryItems;
        this.pantry = pantry;
        this.foods = foods;
        this.matcher = matcher;
        this.plan = plan;
        this.access = access;
        this.clock = clock;
    }

    /** Sichtbare Artikel (offen, gesendet, gekauft) nach Abteilung und Name. */
    @Transactional(readOnly = true)
    public List<ShoppingItem> list(User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return shopping.findByStatusInOrderByCreatedAtAsc(VISIBLE).stream()
                .sorted(Comparator.comparingInt((ShoppingItem i) -> i.getFood() == null
                                ? FoodCategory.OTHER.ordinal() : i.getFood().getCategory().ordinal())
                        .thenComparing(i -> i.getName().toLowerCase(Locale.GERMAN)))
                .toList();
    }

    /** Gekaufte, noch nicht eingeräumte Artikel. */
    @Transactional(readOnly = true)
    public List<ShoppingItem> toStow(User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return shopping.findByStatusInOrderByCreatedAtAsc(EnumSet.of(ShoppingStatus.BOUGHT));
    }

    /**
     * Erzeugt die Liste aus dem Plan neu: Bedarf aller ungekochten Mahlzeiten im Zeitraum minus Vorrat. Ersetzt nur
     * offene Plan-Artikel; was schon an Bring! gesendet ist, wird nicht noch einmal angelegt. Liefert die Anzahl.
     */
    @Transactional
    public int generate(LocalDate from, LocalDate to, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        LocalDate first = from == null ? plan.today() : from;
        LocalDate last = to == null ? MealPlanService.monday(first).plusDays(6) : to;
        if (last.isBefore(first) || last.isAfter(first.plusDays(60))) {
            throw new IllegalArgumentException("Der Zeitraum umfasst 1 bis 60 Tage.");
        }
        List<Meal> inRange = meals.findByDayBetweenOrderByDayAsc(first, last);
        inRange.forEach(m -> {
            if (m.getRecipe() != null) {
                m.getRecipe().getIngredients().forEach(i -> {
                    if (i.getFood() != null) {
                        i.getFood().getName();
                    }
                });
            }
        });
        Set<String> alreadySent = shopping.findByStatusAndOrigin(ShoppingStatus.SENT, ShoppingOrigin.PLAN).stream()
                .map(i -> i.getName().toLowerCase(Locale.GERMAN)).collect(Collectors.toSet());
        List<Needs.Need> needs = Needs.compute(inRange, food -> pantryItems.findByFoodId(food.getId()));
        shopping.deleteAll(shopping.findByStatusAndOrigin(ShoppingStatus.OPEN, ShoppingOrigin.PLAN));
        shopping.flush();
        int created = 0;
        for (Needs.Need n : needs) {
            if (alreadySent.contains(n.name().toLowerCase(Locale.GERMAN))) {
                continue;
            }
            shopping.save(ShoppingItem.fromPlan(truncate(n.name()), n.food(), n.amount(), n.unit()));
            created++;
        }
        return created;
    }

    /** Freie Eingabezeile wie „2 Dosen Tomaten“ oder „Klopapier“. */
    @Transactional
    public ShoppingItem addManual(String line, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        IngredientLine parsed = IngredientParser.parse(line == null ? "" : line);
        if (parsed == null || parsed.name() == null || parsed.name().isBlank()) {
            throw new IllegalArgumentException("Bitte einen Artikel eingeben.");
        }
        if (parsed.amount() != null && (parsed.amount() <= 0 || parsed.amount() > 100_000)) {
            throw new IllegalArgumentException("Die Menge ist ungültig.");
        }
        String name = parsed.name().strip();
        Food food = matcher.match(name).orElse(null);
        Unit unit = parsed.amount() == null ? parsed.unit() : parsed.unit() == null ? Unit.PIECE : parsed.unit();
        return shopping.save(ShoppingItem.manual(truncate(food != null && food.getName().equalsIgnoreCase(name)
                ? food.getName() : name), food, parsed.amount(), unit));
    }

    /** Abhaken in der App = gekauft; ein zweites Mal nimmt es zurück. */
    @Transactional
    public ShoppingItem toggle(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        ShoppingItem item = find(id);
        switch (item.getStatus()) {
            case OPEN, SENT -> item.markBought(Instant.now(clock));
            case BOUGHT -> {
                if (item.getSentAt() != null) {
                    item.markSent(item.getSentAt());
                } else {
                    item.markOpen();
                }
            }
            case STORED -> throw new IllegalArgumentException("Der Artikel ist schon eingeräumt.");
        }
        return item;
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        shopping.delete(find(id));
    }

    /** Räumt einen gekauften Artikel in den Vorrat ein (Menge, Ort und MHD sind bestätigt oder korrigiert). */
    @Transactional
    public PantryItem stow(Long id, double amount, Unit unit, String location, LocalDate bestBefore, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        ShoppingItem item = find(id);
        if (item.getStatus() != ShoppingStatus.BOUGHT) {
            throw new IllegalArgumentException("„" + item.getName() + "“ ist noch nicht als gekauft markiert.");
        }
        PantryItem stored = item.getFood() != null
                ? pantry.add(item.getFood(), amount, unit, location, bestBefore, actor)
                : pantry.add(item.getName(), amount, unit, location, bestBefore, actor);
        item.markStored();
        return stored;
    }

    /** Ordnet einen Artikel dem gescannten Produkt zu; leer, wenn der Barcode niemand kennt. */
    @Transactional
    public boolean assignBarcode(Long id, String code, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_SHOPPING);
        ShoppingItem item = find(id);
        var match = foods.fromBarcode(code, actor);
        match.ifPresent(m -> item.assign(m.food()));
        return match.isPresent();
    }

    /** Vorgeschlagenes MHD: heute + Haltbarkeit des Lebensmittels. */
    public LocalDate suggestedBestBefore(ShoppingItem item) {
        Food food = item.getFood();
        return food == null || food.getShelfDays() == null ? null : plan.today().plusDays(food.getShelfDays());
    }

    private ShoppingItem find(Long id) {
        return shopping.findById(id).orElseThrow(() -> new NoSuchElementException("Artikel nicht gefunden"));
    }

    private static String truncate(String name) {
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}
