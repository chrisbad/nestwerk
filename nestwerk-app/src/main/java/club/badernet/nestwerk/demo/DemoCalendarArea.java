package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.member.FamilyMember;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class DemoCalendarArea implements DemoArea {

    private static final String WEEKLY_MONDAY = "FREQ=WEEKLY;BYDAY=MO";
    private static final String WEEKLY_TUESDAY = "FREQ=WEEKLY;BYDAY=TU";
    private static final String WEEKLY_THURSDAY = "FREQ=WEEKLY;BYDAY=TH";
    private static final String YEARLY = "FREQ=YEARLY";

    private final CalendarEventService events;
    private final EventCategoryService categories;

    DemoCalendarArea(CalendarEventService events, EventCategoryService categories) {
        this.events = events;
        this.categories = categories;
    }

    @Override
    public String name() {
        return "calendar";
    }

    @Override
    public void seed(DemoContext context) {
        LocalDate monday = context.monday();
        DemoFamily family = context.family();
        FamilyMember[] everyone = family.all().toArray(FamilyMember[]::new);

        timed(context, "Schwimmtraining", "Sport", "Hallenbad Remchingen", monday.plusDays(1), "16:30", "17:30",
                WEEKLY_TUESDAY, family.daughter());
        timed(context, "Fußballtraining", "Sport", "Sportplatz", monday.plusDays(3), "17:00", "18:30",
                WEEKLY_THURSDAY, family.son());
        timed(context, "Teamsitzung", "Arbeit", "Büro", monday, "09:30", "10:30", WEEKLY_MONDAY, family.parent());
        timed(context, "Kinderarzt U-Untersuchung", "Arzt", "Praxis Dr. Fischer", monday.plusDays(2), "14:15",
                "15:00", null, family.son());
        timed(context, "Elternabend Klasse 5b", "Schule", "Schule, Raum 112", monday.plusDays(9), "19:00", "20:30",
                null, family.parent(), family.partner());
        timed(context, "Familienessen bei Oma und Opa", "Familie", "Waldstraße 21", monday.plusDays(5), "12:30",
                "15:00", null, everyone);
        timed(context, "Zahnarzt", "Arzt", "Praxis Dr. Weber", monday.plusDays(11), "08:00", "08:45", null,
                family.partner());
        allDay(context, "Geburtstag Oma", "Geburtstag", monday.plusDays(16), YEARLY, family.parent(),
                family.partner());
        allDay(context, "Klassenfahrt Lena", "Schule", monday.plusDays(21), null, family.daughter());
        allDay(context, "Urlaub am Bodensee", "Familie", monday.plusDays(40), null, everyone);
    }

    private void timed(DemoContext context, String title, String category, String location, LocalDate day,
                       String from, String to, String rrule, FamilyMember... participants) {
        events.create(new EventInput(title, null, location, day, LocalTime.parse(from), day, LocalTime.parse(to),
                false, rrule, false, ids(participants), false, categoryId(category, context)), context.actor());
    }

    private void allDay(DemoContext context, String title, String category, LocalDate day, String rrule,
                        FamilyMember... participants) {
        events.create(new EventInput(title, null, null, day, null, day, null, true, rrule, false,
                ids(participants), false, categoryId(category, context)), context.actor());
    }

    private Long categoryId(String name, DemoContext context) {
        return categories.findByName(name, context.actor()).map(EventCategory::getId).orElse(null);
    }

    private static Set<Long> ids(FamilyMember... members) {
        return Arrays.stream(members).map(FamilyMember::getId).collect(Collectors.toSet());
    }
}
