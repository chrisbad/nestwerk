package club.badernet.nestwerk.item;

import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** All calendar items of all types, collected from the {@link CalendarItemSource}s. */
@Component
public class CalendarItems {

    private final List<CalendarItemSource> sources;

    public CalendarItems(List<CalendarItemSource> sources) {
        this.sources = sources;
    }

    /**
     * Items of the days {@code from} to {@code to} (inclusive), sorted by start. An empty {@code types} means all
     * types; {@code memberId} limits to items of that person ({@code null} = everybody).
     */
    public List<CalendarItem> between(LocalDate from, LocalDate to, User viewer, Set<ItemType> types, Long memberId) {
        return sources.stream()
                .filter(source -> types.isEmpty() || types.contains(source.type()))
                .flatMap(source -> source.items(from, to, viewer).stream())
                .filter(item -> memberId == null || item.memberIds().contains(memberId))
                .sorted(Comparator.comparing(CalendarItem::start).thenComparing(CalendarItem::title))
                .toList();
    }

    public Optional<CalendarItem> find(ItemType type, Long id, User viewer) {
        return sources.stream().filter(source -> source.type() == type)
                .findFirst().flatMap(source -> source.find(id, viewer));
    }
}
