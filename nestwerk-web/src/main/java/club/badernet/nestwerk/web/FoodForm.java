package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodCategory;
import club.badernet.nestwerk.kitchen.FoodInput;

/** Formular für Lebensmittel; Zahlen mit Komma oder Punkt. */
public class FoodForm {

    private String name;
    private FoodCategory category = FoodCategory.OTHER;
    private String kcal;
    private String protein;
    private String fat;
    private String carbs;
    private String pieceGrams;
    private String packageGrams;
    private String shelfDays;
    private String barcode;

    public static FoodForm from(Food f) {
        FoodForm form = new FoodForm();
        form.name = f.getName();
        form.category = f.getCategory();
        form.kcal = text(f.getKcal());
        form.protein = text(f.getProtein());
        form.fat = text(f.getFat());
        form.carbs = text(f.getCarbs());
        form.pieceGrams = text(f.getPieceGrams());
        form.packageGrams = text(f.getPackageGrams());
        form.shelfDays = f.getShelfDays() == null ? "" : f.getShelfDays().toString();
        form.barcode = f.getBarcode();
        return form;
    }

    public FoodInput toInput() {
        Double days = number(shelfDays, "Haltbarkeit");
        return new FoodInput(name, category, number(kcal, "kcal"), number(protein, "Eiweiß"), number(fat, "Fett"),
                number(carbs, "Kohlenhydrate"), number(pieceGrams, "Stückgewicht"),
                number(packageGrams, "Packungsgröße"), days == null ? null : (int) Math.round(days), barcode);
    }

    private static String text(Double v) {
        return v == null ? "" : Amounts.plain(v);
    }

    private static Double number(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Amounts.parse(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " ist keine Zahl.");
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public FoodCategory getCategory() { return category; }
    public void setCategory(FoodCategory category) { this.category = category; }
    public String getKcal() { return kcal; }
    public void setKcal(String kcal) { this.kcal = kcal; }
    public String getProtein() { return protein; }
    public void setProtein(String protein) { this.protein = protein; }
    public String getFat() { return fat; }
    public void setFat(String fat) { this.fat = fat; }
    public String getCarbs() { return carbs; }
    public void setCarbs(String carbs) { this.carbs = carbs; }
    public String getPieceGrams() { return pieceGrams; }
    public void setPieceGrams(String pieceGrams) { this.pieceGrams = pieceGrams; }
    public String getPackageGrams() { return packageGrams; }
    public void setPackageGrams(String packageGrams) { this.packageGrams = packageGrams; }
    public String getShelfDays() { return shelfDays; }
    public void setShelfDays(String shelfDays) { this.shelfDays = shelfDays; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
}
