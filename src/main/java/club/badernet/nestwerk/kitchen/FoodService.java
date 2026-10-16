package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/** Pflege der Lebensmittel. */
@Service
public class FoodService {

    private final FoodRepository foods;
    private final KitchenAccess access;

    public FoodService(FoodRepository foods, KitchenAccess access) {
        this.foods = foods;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<Food> search(String q, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return q == null || q.isBlank() ? foods.findAllByOrderByNameAsc() : foods.search(q.strip());
    }

    @Transactional(readOnly = true)
    public Food get(Long id) {
        return foods.findById(id).orElseThrow(() -> new NoSuchElementException("Lebensmittel nicht gefunden"));
    }

    @Transactional
    public Food create(FoodInput input, FoodSource source, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        String name = requireName(input, null);
        Food food = new Food(name, input.category() == null ? FoodCategory.OTHER : input.category(), source);
        apply(food, input);
        return foods.save(food);
    }

    @Transactional
    public Food update(Long id, FoodInput input, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        Food food = get(id);
        food.setName(requireName(input, id));
        food.setCategory(input.category() == null ? FoodCategory.OTHER : input.category());
        apply(food, input);
        if (food.getSource() == FoodSource.SEED) {
            food.setSource(FoodSource.MANUAL);
        }
        return food;
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        foods.delete(get(id));
    }

    private String requireName(FoodInput input, Long ownId) {
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Bitte einen Namen angeben.");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("Der Name ist zu lang.");
        }
        foods.findByNameIgnoreCase(name).filter(f -> !f.getId().equals(ownId)).ifPresent(f -> {
            throw new IllegalArgumentException("„" + f.getName() + "“ gibt es schon.");
        });
        return name;
    }

    private void apply(Food food, FoodInput input) {
        food.setNutrition(nonNegative(input.kcal()), nonNegative(input.protein()), nonNegative(input.fat()),
                nonNegative(input.carbs()));
        food.setPieceGrams(positive(input.pieceGrams()));
        food.setPackageGrams(positive(input.packageGrams()));
        food.setShelfDays(input.shelfDays() == null || input.shelfDays() <= 0 ? null : input.shelfDays());
        String barcode = input.barcode() == null ? "" : input.barcode().replaceAll("\\s", "");
        if (!barcode.isEmpty()) {
            if (!barcode.matches("\\d{6,14}")) {
                throw new IllegalArgumentException("Der Barcode besteht aus 6 bis 14 Ziffern.");
            }
            foods.findByBarcode(barcode).filter(f -> !f.equals(food)).ifPresent(f -> {
                throw new IllegalArgumentException("Der Barcode gehört schon zu „" + f.getName() + "“.");
            });
        }
        food.setBarcode(barcode.isEmpty() ? null : barcode);
    }

    private static Double nonNegative(Double v) {
        if (v != null && v < 0) {
            throw new IllegalArgumentException("Nährwerte dürfen nicht negativ sein.");
        }
        return v;
    }

    private static Double positive(Double v) {
        return v == null || v <= 0 ? null : v;
    }
}
