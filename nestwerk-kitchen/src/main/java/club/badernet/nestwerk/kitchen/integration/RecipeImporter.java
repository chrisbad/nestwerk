package club.badernet.nestwerk.kitchen.integration;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Liest schema.org/Recipe (JSON-LD) aus Rezeptseiten, auch Cookidoo, Chefkoch und Blogs. */
@Component
public class RecipeImporter {

    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Map<String, String> ENTITIES = Map.ofEntries(Map.entry("&amp;", "&"),
            Map.entry("&quot;", "\""), Map.entry("&apos;", "'"), Map.entry("&#39;", "'"), Map.entry("&lt;", "<"),
            Map.entry("&gt;", ">"), Map.entry("&nbsp;", " "), Map.entry("&shy;", ""), Map.entry("&auml;", "ä"),
            Map.entry("&ouml;", "ö"), Map.entry("&uuml;", "ü"), Map.entry("&Auml;", "Ä"), Map.entry("&Ouml;", "Ö"),
            Map.entry("&Uuml;", "Ü"), Map.entry("&szlig;", "ß"), Map.entry("&frac12;", "½"),
            Map.entry("&frac14;", "¼"), Map.entry("&frac34;", "¾"), Map.entry("&deg;", "°"));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_DEPTH = 6;

    private final PageFetcher fetcher;

    public RecipeImporter(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public ImportedRecipe importFrom(String url) {
        String html = fetcher.fetch(url);
        return parse(html, url.strip());
    }

    public static ImportedRecipe parse(String html, String sourceUrl) {
        Matcher m = LD_JSON.matcher(html);
        boolean sawJson = false;
        while (m.find()) {
            JsonNode root;
            try {
                root = JSON.readTree(m.group(1).strip());
            } catch (JacksonException e) {
                continue;
            }
            sawJson = true;
            JsonNode recipe = findRecipe(root, 0);
            if (recipe != null) {
                return toRecipe(recipe, sourceUrl);
            }
        }
        throw new ImportException(sawJson ? "Auf der Seite wurde kein Rezept gefunden."
                : "Auf der Seite wurde kein Rezept gefunden (keine Rezeptdaten im Format schema.org).");
    }

    private static JsonNode findRecipe(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findRecipe(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        if (isRecipe(node.path("@type"))) {
            return node;
        }
        for (String key : List.of("@graph", "mainEntity", "itemListElement")) {
            JsonNode found = findRecipe(node.get(key), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isRecipe(JsonNode type) {
        if (type.isString()) {
            return type.asString().equalsIgnoreCase("Recipe");
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isString() && t.asString().equalsIgnoreCase("Recipe")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ImportedRecipe toRecipe(JsonNode r, String sourceUrl) {
        String title = clean(text(r.path("name")));
        if (title.isEmpty()) {
            throw new ImportException("Das Rezept auf der Seite hat keinen Titel.");
        }
        List<String> ingredients = new ArrayList<>();
        JsonNode list = r.path("recipeIngredient").isMissingNode() ? r.path("ingredients") : r.path("recipeIngredient");
        if (list.isArray()) {
            for (JsonNode i : list) {
                String line = clean(text(i));
                if (!line.isEmpty()) {
                    ingredients.add(line);
                }
            }
        } else if (list.isString()) {
            ingredients.add(clean(list.asString()));
        }
        String description = clean(text(r.path("description")));
        return new ImportedRecipe(truncate(title, 200), description.isEmpty() ? null : truncate(description, 1000),
                servings(r.path("recipeYield")), minutes(r), tags(r.path("keywords")), sourceUrl,
                steps(r.path("recipeInstructions")), List.copyOf(ingredients));
    }

    private static Integer servings(JsonNode yield) {
        JsonNode first = yield.isArray() ? yield.path(0) : yield;
        if (first.isNumber()) {
            int n = first.asInt();
            return n >= 1 && n <= 100 ? n : null;
        }
        Matcher m = NUMBER.matcher(text(first));
        if (m.find()) {
            int n = Integer.parseInt(m.group());
            return n >= 1 && n <= 100 ? n : null;
        }
        return null;
    }

    private static Integer minutes(JsonNode r) {
        Long total = duration(r.path("totalTime"));
        if (total == null) {
            Long prep = duration(r.path("prepTime"));
            Long cook = duration(r.path("cookTime"));
            if (prep != null || cook != null) {
                total = (prep == null ? 0 : prep) + (cook == null ? 0 : cook);
            }
        }
        return total == null || total < 1 || total > 2880 ? null : total.intValue();
    }

    private static Long duration(JsonNode node) {
        String value = text(node).strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Duration.parse(value.replace("P0DT", "PT")).toMinutes();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String tags(JsonNode keywords) {
        List<String> tags = new ArrayList<>();
        if (keywords.isArray()) {
            keywords.forEach(k -> tags.add(clean(text(k))));
        } else {
            for (String k : text(keywords).split(",")) {
                tags.add(clean(k));
            }
        }
        String joined = String.join(", ", tags.stream().filter(t -> !t.isEmpty()).limit(10).toList());
        return joined.isEmpty() ? null : truncate(joined, 500);
    }

    private static String steps(JsonNode instructions) {
        List<String> blocks = new ArrayList<>();
        List<String> loose = new ArrayList<>();
        if (instructions.isString()) {
            for (String line : instructions.asString().split("\\R|<br\\s*/?>|</p>")) {
                String cleaned = clean(line);
                if (!cleaned.isEmpty()) {
                    loose.add(cleaned);
                }
            }
        } else if (instructions.isArray()) {
            for (JsonNode step : instructions) {
                if ("HowToSection".equals(text(step.path("@type")))) {
                    if (!loose.isEmpty()) {
                        blocks.add(numbered(loose));
                        loose = new ArrayList<>();
                    }
                    List<String> inner = new ArrayList<>();
                    for (JsonNode s : step.path("itemListElement")) {
                        String t = stepText(s);
                        if (!t.isEmpty()) {
                            inner.add(t);
                        }
                    }
                    String name = clean(text(step.path("name")));
                    blocks.add((name.isEmpty() ? "" : "**" + name + "**\n\n") + numbered(inner));
                } else {
                    String t = stepText(step);
                    if (!t.isEmpty()) {
                        loose.add(t);
                    }
                }
            }
        }
        if (!loose.isEmpty()) {
            blocks.add(numbered(loose));
        }
        String result = String.join("\n\n", blocks).strip();
        return result.isEmpty() ? null : result;
    }

    private static String stepText(JsonNode step) {
        if (step.isString()) {
            return clean(step.asString());
        }
        return clean(text(step.has("text") ? step.path("text") : step.path("name")));
    }

    private static String numbered(List<String> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". ").append(steps.get(i));
        }
        return sb.toString();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isString() || node.isNumber()) {
            return node.asString();
        }
        if (node.isArray() && !node.isEmpty()) {
            return text(node.get(0));
        }
        return "";
    }

    /** Entfernt HTML-Tags, löst Entitäten auf und fasst Leerraum zusammen. */
    static String clean(String raw) {
        String s = raw.replaceAll("<[^>]+>", "");
        for (Map.Entry<String, String> e : ENTITIES.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(sb);
        return sb.toString().replace("\u00ad", "").replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
