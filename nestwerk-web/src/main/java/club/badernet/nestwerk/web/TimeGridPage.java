package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerBands;
import club.badernet.nestwerk.blocker.BlockerZones;
import club.badernet.nestwerk.blocker.BusyBlock;
import club.badernet.nestwerk.blocker.BusyBlocks;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds the model of the time grid views (day, three days, week). */
@Component
public class TimeGridPage {

    /**
     * Entries sharing a day column with more neighbours than this are drawn compact (start time only), because the
     * columns of the week are narrow and those of a single day wide.
     */
    private static final Map<String, Integer> NARROW_FROM = Map.of("day", 3, "days3", 2, "week", 1);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MINUTES_PER_DAY = 24 * 60;

    private final CalendarPageData pageData;
    private final CalendarEventService events;
    private final BlockerBands bands;
    private final BlockerZones zones;
    private final BusyBlocks busyBlocks;
    private final ZoneId zone;

    public TimeGridPage(CalendarPageData pageData, CalendarEventService events, BlockerBands bands,
                        BlockerZones zones, BusyBlocks busyBlocks, CalendarSettings settings) {
        this.pageData = pageData;
        this.events = events;
        this.bands = bands;
        this.zones = zones;
        this.busyBlocks = busyBlocks;
        this.zone = settings.zone();
    }

    /**
     * Adds days, all-day lanes and everything the toolbar and legend need. Split by person, every day gets one column
     * per family member and one for "Alle"; an entry appears in the column of each of its members.
     */
    public void fill(Model model, List<LocalDate> days, String view, GridSplit split, User viewer) {
        pageData.addCommon(model, view, viewer);
        Map<Long, FamilyMember> people = pageData.memberById();
        Map<Long, EventCategory> categories = pageData.categoryById(viewer);
        LocalDate first = days.getFirst();
        LocalDate last = days.getLast();

        List<GridEntry> timed = new ArrayList<>();
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
                .forEach(list -> list.forEach(t -> allDay.add(new GridEntry.DueTodo(t, pageData.canEdit(t, viewer)))));
        CalendarChores chores = pageData.addChores(model, first, last, viewer);
        chores.timed().values().forEach(list -> list.forEach(c -> timed.add(new GridEntry.Chore(c))));
        chores.untimed().values().forEach(list -> list.forEach(c -> allDay.add(new GridEntry.DayChore(c))));

        List<GridPerson> columns = split == GridSplit.BY_PERSON ? persons(people) : List.of(GridPerson.ALL);
        List<Map<LocalDate, List<TimeGridLayout.Placed<GridEntry>>>> placed = columns.stream()
                .map(person -> TimeGridLayout.layout(of(person, split, timed, people), days, this::start, this::end))
                .toList();
        Map<LocalDate, List<GridDay.GridZone>> zonesByDay = zones(days, viewer);
        Map<LocalDate, List<GridDay.OverlapZone>> overlapsByDay = overlapZones(timed, days, people);
        Map<LocalDate, List<BlockerBands.Band>> bandsByDay = bands.sourceBands(first, last, viewer);
        Map<LocalDate, List<BusyBlock>> blocksByDay = busyBlocks.between(first, last, viewer).stream()
                .collect(Collectors.groupingBy(b -> b.start().atZone(zone).toLocalDate()));
        LocalDate today = pageData.today();
        List<GridDay> gridDays = days.stream().map(d -> new GridDay(d, d.equals(today), isWeekend(d),
                bandsByDay.getOrDefault(d, List.of()), dayColumns(d, columns, split, placed,
                        zonesByDay.getOrDefault(d, List.of()), blocks(blocksByDay.getOrDefault(d, List.of()),
                                people), overlapsByDay.getOrDefault(d, List.of())))).toList();
        List<AllDayItem> items = allDayItems(allDay, days, columns, split, people);

        model.addAttribute("gridDays", gridDays);
        model.addAttribute("persons", columns);
        model.addAttribute("splitByPerson", split == GridSplit.BY_PERSON);
        model.addAttribute("allDayLanes", items);
        model.addAttribute("laneCount", items.stream().mapToInt(l -> l.lane() + 1).max().orElse(0));
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

    private static List<GridPerson> persons(Map<Long, FamilyMember> people) {
        List<GridPerson> result = new ArrayList<>();
        people.values().forEach(m -> result.add(new GridPerson(m.getId(), m.getName(), m.getColor())));
        result.add(GridPerson.ALL);
        return List.copyOf(result);
    }

    /** The entries shown in the column of {@code person}; unknown members count as none. */
    private static List<GridEntry> of(GridPerson person, GridSplit split, List<GridEntry> entries,
                                      Map<Long, FamilyMember> people) {
        if (split == GridSplit.NONE) {
            return entries;
        }
        return entries.stream().filter(e -> {
            Set<Long> known = e.memberIds().stream().filter(people::containsKey).collect(Collectors.toSet());
            return person.isAll() ? known.isEmpty() : known.contains(person.memberId());
        }).toList();
    }

    private static List<GridDay.Column> dayColumns(LocalDate day, List<GridPerson> columns, GridSplit split,
                                                   List<Map<LocalDate, List<TimeGridLayout.Placed<GridEntry>>>> placed,
                                                   List<GridDay.GridZone> zones, List<MemberBlock> blocks,
                                                   List<GridDay.OverlapZone> overlaps) {
        List<GridDay.Column> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Long member = columns.get(i).memberId();
            List<GridDay.GridZone> own = split == GridSplit.NONE ? zones
                    : zones.stream().filter(z -> z.memberId().equals(member)).toList();
            List<GridDay.GridBlock> ownBlocks = blocks.stream()
                    .filter(b -> split == GridSplit.NONE || b.memberId().equals(member))
                    .map(MemberBlock::block).toList();
            List<GridDay.OverlapZone> ownOverlaps = split == GridSplit.NONE ? overlaps
                    : overlaps.stream().filter(z -> z.memberId().equals(member)).toList();
            result.add(new GridDay.Column(member, placed.get(i).getOrDefault(day, List.of()), own, ownBlocks,
                    ownOverlaps));
        }
        return List.copyOf(result);
    }

    /**
     * All-day entries in rows. Split by person, the columns of one person are not next to each other, so an entry
     * spanning several days is shown once per day in that person's column.
     */
    private List<AllDayItem> allDayItems(List<GridEntry> allDay, List<LocalDate> days, List<GridPerson> columns,
                                         GridSplit split, Map<Long, FamilyMember> people) {
        List<AllDayItem> result = new ArrayList<>();
        int width = columns.size();
        for (int p = 0; p < width; p++) {
            Long member = columns.get(p).memberId();
            for (TimeGridLayout.Lane<GridEntry> lane : TimeGridLayout.lanes(of(columns.get(p), split, allDay, people),
                    days, GridEntry::firstDay, GridEntry::lastDay)) {
                if (split == GridSplit.NONE) {
                    result.add(new AllDayItem(lane.entry(), lane.firstColumn(), lane.span(), lane.lane(),
                            lane.continuesBefore(), lane.continuesAfter(), null));
                    continue;
                }
                int lastDay = lane.firstColumn() + lane.span() - 1;
                for (int d = lane.firstColumn(); d <= lastDay; d++) {
                    result.add(new AllDayItem(lane.entry(), d * width + p, 1, lane.lane(),
                            d > lane.firstColumn() || lane.continuesBefore(), d < lastDay || lane.continuesAfter(),
                            member));
                }
            }
        }
        return List.copyOf(result);
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

    /** One person's share of a timed event or chore, as a candidate for the overlap zones. */
    private record Occupied(GridEntry entry, Long memberId, String key, String kind, boolean flag, String label) {
    }

    /** The times of the timed events and chores per member and day; the grid shows the relevant ones while dragging. */
    private Map<LocalDate, List<GridDay.OverlapZone>> overlapZones(List<GridEntry> timed, List<LocalDate> days,
                                                                   Map<Long, FamilyMember> people) {
        List<Occupied> occupied = new ArrayList<>();
        for (GridEntry entry : timed) {
            for (Long member : entry.memberIds()) {
                if (!people.containsKey(member)) {
                    continue;
                }
                switch (entry) {
                    case GridEntry.Event e -> occupied.add(new Occupied(e, member, "event:" + e.occurrence().eventId(),
                            "event", e.occurrence().noOverlap(), "dem Termin „" + e.occurrence().title() + "“"));
                    case GridEntry.Chore c -> occupied.add(new Occupied(c, member, "chore:" + c.chore().id(), "chore",
                            c.chore().noOverlap(), "der Hausarbeit „" + c.chore().title() + "“"));
                    default -> {
                    }
                }
            }
        }
        Map<LocalDate, List<GridDay.OverlapZone>> result = new LinkedHashMap<>();
        TimeGridLayout.layout(occupied, days, o -> start(o.entry()), o -> end(o.entry())).forEach((day, list) ->
                result.put(day, list.stream().map(p -> new GridDay.OverlapZone(p.startMinute(), p.endMinute(),
                        p.entry().memberId(), p.entry().key(), p.entry().kind(), p.entry().flag(),
                        people.get(p.entry().memberId()).getName() + " · überschneidet sich mit "
                                + p.entry().label())).toList()));
        return result;
    }

    /** A drawn block with the person it belongs to, to pick it for the person's column. */
    private record MemberBlock(Long memberId, GridDay.GridBlock block) {
    }

    /** The blocks of a day as drawn blocks; in an unsplit column every affected person gets a stripe of its own. */
    private List<MemberBlock> blocks(List<BusyBlock> day, Map<Long, FamilyMember> people) {
        List<Long> stripeOrder = day.stream().map(BusyBlock::memberId).distinct().sorted().toList();
        return day.stream().map(b -> {
            FamilyMember member = people.get(b.memberId());
            GridDay.GridBlock block = new GridDay.GridBlock(minuteOfDay(b.start()), endMinute(b.end()),
                    stripeOrder.indexOf(b.memberId()), stripeOrder.size(), b.kind(),
                    member == null ? null : member.getColor(),
                    (member == null ? "" : member.getName() + " · ") + b.label(),
                    TIME.format(b.start().atZone(zone)) + "–" + TIME.format(b.end().atZone(zone)));
            return new MemberBlock(b.memberId(), block);
        }).toList();
    }

    private int minuteOfDay(Instant instant) {
        ZonedDateTime time = instant.atZone(zone);
        return time.getHour() * 60 + time.getMinute();
    }

    /** A block ending at midnight ends the day, not at minute 0. */
    private int endMinute(Instant instant) {
        int minute = minuteOfDay(instant);
        return minute == 0 ? MINUTES_PER_DAY : minute;
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
        return entry.startsAt().atZone(zone);
    }

    private ZonedDateTime end(GridEntry entry) {
        return entry.endsAt().atZone(zone);
    }

    private static boolean isWeekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
