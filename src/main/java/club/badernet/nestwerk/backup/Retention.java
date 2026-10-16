package club.badernet.nestwerk.backup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Welche Sicherungen nach der Aufbewahrungsregel wegkönnen. Hochgeladene Dateien bleiben immer. */
public final class Retention {

    /** Eine Sicherung mit Name und Zeitpunkt. */
    public record Item(String name, Instant time) {
    }

    private static final Pattern STAMP = Pattern.compile("^nestwerk-(\\d{8}-\\d{6})");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Retention() {
    }

    public static boolean managed(String name) {
        return name.startsWith("nestwerk-") && !name.startsWith("nestwerk-hochgeladen-");
    }

    /** Zeitpunkt aus dem Dateinamen ({@code nestwerk-JJJJMMTT-HHMMSS…}); Änderungszeiten am Ziel sind unzuverlässig. */
    static Instant timeOf(Item item, ZoneId zone) {
        Matcher m = STAMP.matcher(item.name());
        if (m.find()) {
            try {
                return LocalDateTime.parse(m.group(1), FORMAT).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // fällt auf die Änderungszeit zurück
            }
        }
        return item.time();
    }

    public static Set<String> toDelete(List<Item> items, BackupSettings.RetentionRule rule, ZoneId zone) {
        List<Item> sorted = items.stream().filter(i -> managed(i.name()))
                .map(i -> new Item(i.name(), timeOf(i, zone)))
                .sorted(Comparator.comparing(Item::time).reversed()).toList();
        Set<String> keep = new HashSet<>();
        sorted.stream().limit(rule.keepLast()).forEach(i -> keep.add(i.name()));
        Set<String> weeks = new LinkedHashSet<>();
        Set<String> months = new LinkedHashSet<>();
        for (Item item : sorted) {
            ZonedDateTime t = item.time().atZone(zone);
            String week = t.get(IsoFields.WEEK_BASED_YEAR) + "-W" + t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            if (weeks.size() < rule.keepWeekly() && weeks.add(week)) {
                keep.add(item.name());
            }
            String month = t.getYear() + "-" + t.getMonthValue();
            if (months.size() < rule.keepMonthly() && months.add(month)) {
                keep.add(item.name());
            }
        }
        Set<String> delete = new LinkedHashSet<>();
        sorted.stream().map(Item::name).filter(n -> !keep.contains(n)).forEach(delete::add);
        return delete;
    }
}
