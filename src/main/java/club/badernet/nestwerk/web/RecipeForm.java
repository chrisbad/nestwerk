package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.IngredientInput;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeInput;
import club.badernet.nestwerk.kitchen.RecipeIngredient;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.kitchen.Unit;

import java.util.ArrayList;
import java.util.List;

/** Formular für Rezepte; Zutaten als Zeilen, dazu ein Feld zum Einfügen ganzer Zutatenlisten. */
public class RecipeForm {

    /** Eine Zutatenzeile im Formular. */
    public static class Row {
        private String amount;
        private String unit;
        private String name;
        private String note;

        public Row() {
        }

        Row(String amount, String unit, String name, String note) {
            this.amount = amount;
            this.unit = unit;
            this.name = name;
            this.note = note;
        }

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    private String title;
    private String description;
    private Integer servings = 2;
    private Integer minutes;
    private String tags;
    private String sourceUrl;
    private String steps;
    private List<Row> rows = new ArrayList<>();
    private String paste;

    public static RecipeForm from(Recipe recipe) {
        RecipeForm form = new RecipeForm();
        form.title = recipe.getTitle();
        form.description = recipe.getDescription();
        form.servings = recipe.getServings();
        form.minutes = recipe.getMinutes();
        form.tags = recipe.getTags();
        form.sourceUrl = recipe.getSourceUrl();
        form.steps = recipe.getSteps();
        for (RecipeIngredient i : recipe.getIngredients()) {
            form.rows.add(new Row(i.getAmount() == null ? "" : Amounts.plain(i.getAmount()),
                    i.getUnit() == null ? "" : i.getUnit().name(), i.getText(), i.getNote()));
        }
        return form;
    }

    public void addLines(List<IngredientInput> lines) {
        for (IngredientInput i : lines) {
            rows.add(new Row(i.amount() == null ? "" : Amounts.plain(i.amount()),
                    i.unit() == null ? "" : i.unit().name(), i.name(), i.note()));
        }
    }

    /** Leere Zeilen zum Ausfüllen anhängen, damit das Formular nie ohne Zeile ist. */
    public void padRows(int free) {
        for (int k = 0; k < free; k++) {
            rows.add(new Row("", "", "", ""));
        }
    }

    public RecipeInput toInput() {
        List<IngredientInput> ingredients = new ArrayList<>();
        for (Row r : rows) {
            if (r.name == null || r.name.isBlank()) {
                continue;
            }
            ingredients.add(new IngredientInput(amount(r.amount, r.name), Unit.parse(r.unit).orElse(null), r.name,
                    r.note, null));
        }
        ingredients.addAll(RecipeService.parseLines(paste));
        return new RecipeInput(title, description, servings, minutes, tags, sourceUrl, steps, ingredients);
    }

    private static Double amount(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Amounts.parse(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Die Menge von „" + name.strip() + "“ ist keine Zahl.");
        }
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getServings() { return servings; }
    public void setServings(Integer servings) { this.servings = servings; }
    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer minutes) { this.minutes = minutes; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSteps() { return steps; }
    public void setSteps(String steps) { this.steps = steps; }
    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }
    public String getPaste() { return paste; }
    public void setPaste(String paste) { this.paste = paste; }
}
