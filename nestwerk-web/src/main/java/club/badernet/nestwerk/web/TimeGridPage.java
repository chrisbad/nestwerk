package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerBands;
import club.badernet.nestwerk.blocker.BlockerZones;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.TimeGridLayout;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the model of the time grid views (day, three days, week). */
@Component
public class TimeGridPage {

    /**
     * Entries sharing a day column with more neighbours than this are drawn compact (start time only), because the
     * columns of the week are narrow and those of a single day wide.
     */
    private static final Map<String, Integer> NARROW_FROM = Map.of("day", 3, "days3", 2, "week", 1);

    private final CalendarPageData pageData;
    private final CalendarEventService events;
    private final BlockerBands bands;
    private final BlockerZones zones;
    private final ZoneId zone;

    public TimeGridPage(CalendarPageData pageData, CalendarEventService events, BlockerBands bands,
                        BlockerZones zones, CalendarSettings settings) {
        this.pageData = pageData;
        this.events = events;
        this.bands = bands;
        this.zones = zones;
        this.zone = settings.zone();
    }

    /** Adds days, all-day lanes and everything the toolbar and legend need. */
    public void fill(Model model, List<LocalDate> days, String view, User viewer) {
        pageData.addCommon(model, view, viewer);
        Map<Long, FamilyMember> people = pageData.memberById();
        Map<Long, EventCategory> categories = pageData.categoryById(viewer);
        LocalDate first = days.getFirst();
        LocalDate last = days.getLast();

        List<GridEntry.Event> timed = new ArrayList<>();
        List<GridEntry> allDay = new ArrayList<>();
        for (Occurrence o : distinct(pageData.occurrences(days, viewer).values())) {
            GridEntry.Event entry = event(o, people, categories, viewer);
            if (o.allDay()) {
                allDay.add(entry);
            } else {
                timed.add(entry);
            }
        }
        pageData.addTodos(model, first, last, viewer).values()
                .forEach(list -> list.forEach(t -> allDay.add(new GridEntry.DueTodo(t))));
        List<GridEntry> gridTimed = new ArrayList<>(timed);
        pageData.addChores(model, first, last, viewer).values()
                .forEach(list -> list.forEach(c -> gridTimed.add(new GridEntry.Chore(c))));

        Map<LocalDate, List<TimeGridLayout.Placed<GridEntry>>> placed = TimeGridLayout.layout(gridTimed, days,
                this::start, this::end);
        Map<LocalDate, List<GridDay.GridZone>> zonesByDay = zones(days, viewer);
        Map<LocalDate, List<BlockerBands.Band>> bandsByDay = bands.between(first, last, viewer);
        LocalDate today = pageData.today();
        List<GridDay> gridDays = days.stream().map(d -> new GridDay(d, d.equals(today), isWeekend(d),
                placed.get(d), bandsByDay.getOrDefault(d, List.of()), zonesByDay.get(d))).toList();
        List<TimeGridLayout.Lane<GridEntry>> lanes = TimeGridLayout.lanes(allDay, days, this::firstDay,
                this::lastDay);

        model.addAttribute("gridDays", gridDays);
        model.addAttribute("allDayLanes", lanes);
        model.addAttribute("laneCount", lanes.stream().mapToInt(l -> l.lane() + 1).max().orElse(0));
        ZonedDateTime now = ZonedDateTime.now(zone);
        model.addAttribute("nowMinute", now.getHour() * 60 + now.getMinute());
        model.addAttribute("slotFine", SlotSize.FINE.minutes());
        model.addAttribute("slotCoarse", SlotSize.COARSE.minutes());
        model.addAttribute("narrowColumns", NARROW_FROM.getOrDefault(view, 1));
        model.addAttribute("canCreate", events.canCreate(viewer));
        model.addAttribute("allMembers", List.copyOf(people.values()));
        model.addAttribute("allCategories", List.copyOf(categories.values()));
        model.addAttribute("first", first);
        model.addAttribute("last", last);
    }

    private GridEntry.Event event(Occurrence o, Map<Long, FamilyMember> people, Map<Long, EventCategory> categories,
                                  User viewer) {
        EventCategory category = o.categoryId() == null ? null : categories.get(o.categoryId());
        String color = category != null ? category.getColor().hex()
                : o.memberIds().stream().map(people::get).filter(java.util.Objects::nonNull).findFirst()
                .map(FamilyMember::getColor).orElse(null);
        return new GridEntry.Event(o, o.start().withZoneSameInstant(zone).toLocalDateTime(),
                o.end().withZoneSameInstant(zone).toLocalDateTime(), color,
                category == null ? null : category.getName(), events.canEdit(o.createdBy(), viewer),
                events.canDelete(o.createdBy(), viewer));
    }

    private Map<LocalDate, List<GridDay.GridZone>> zones(List<LocalDate> days, User viewer) {
        Map<LocalDate, List<GridDay.GridZone>> result = new LinkedHashMap<>();
        TimeGridLayout.layout(zones.between(days.getFirst(), days.getLast(), viewer), days,
                z -> z.start().atZone(zone), z -> z.end().atZone(zone)).forEach((day, list) ->
                result.put(day, list.stream().map(p -> new GridDay.GridZone(p.startMinute(), p.endMinute(),
                        p.entry().memberId(), p.entry().strength().name().toLowerCase(Locale.ROOT),
                        p.entry().label())).toList()));
        return result;
    }

    /** An occurrence spanning several days is listed for each of them; the grid needs it once. */
    private static List<Occurrence> distinct(Collection<List<Occurrence>> byDay) {
        LinkedHashSet<Occurrence> unique = new LinkedHashSet<>();
        byDay.forEach(unique::addAll);
        return List.copyOf(unique);
    }

    private ZonedDateTime start(GridEntry entry) {
        return switch (entry) {
            case GridEntry.Event e -> e.startsAt().atZone(zone);
            case GridEntry.Chore c -> c.chore().startsAt().atZone(zone);
            case GridEntry.DueTodo t -> t.todo().getDueOn().atStartOfDay(zone);
        };
    }

    private ZonedDateTime end(GridEntry entry) {
        return switch (entry) {
            case GridEntry.Event e -> e.endsAt().atZone(zone);
            case GridEntry.Chore c -> c.chore().endsAt().atZone(zone);
            case GridEntry.DueTodo t -> t.todo().getDueOn().plusDays(1).atStartOfDay(zone);
        };
    }

    private LocalDate firstDay(GridEntry entry) {
        return switch (entry) {
            case GridEntry.Event e -> e.startsAt().toLocalDate();
            case GridEntry.Chore c -> c.chore().day();
            case GridEntry.DueTodo t -> t.todo().getDueOn();
        };
    }

    private LocalDate lastDay(GridEntry entry) {
        return switch (entry) {
            case GridEntry.Event e -> e.lastDay();
            case GridEntry.Chore c -> c.chore().day();
            case GridEntry.DueTodo t -> t.todo().getDueOn();
        };
    }

    private static boolean isWeekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
