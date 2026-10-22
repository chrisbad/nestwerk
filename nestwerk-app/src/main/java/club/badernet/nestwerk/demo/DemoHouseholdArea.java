package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.household.RewardService;
import club.badernet.nestwerk.member.FamilyMember;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
class DemoHouseholdArea implements DemoArea {

    private static final int INTERVAL_FORTNIGHT = 14;
    private static final int INTERVAL_MONTH = 28;

    private final ChoreService chores;
    private final RewardService rewards;

    DemoHouseholdArea(ChoreService chores, RewardService rewards) {
        this.chores = chores;
        this.rewards = rewards;
    }

    @Override
    public String name() {
        return "household";
    }

    @Override
    public void seed(DemoContext context) {
        LocalDate monday = context.monday();
        DemoFamily family = context.family();
        List<Long> everyone = family.all().stream().map(FamilyMember::getId).toList();
        List<Long> children = family.children().stream().map(FamilyMember::getId).toList();
        List<Long> adults = List.of(family.parent().getId(), family.partner().getId());

        recurring(context, "Müll rausbringen", "Küche", 5, "FREQ=WEEKLY;BYDAY=MO,TH", monday,
                Distribution.ROTATION, children);
        recurring(context, "Spülmaschine ausräumen", "Küche", 10, "FREQ=DAILY", monday, Distribution.ROTATION,
                everyone);
        recurring(context, "Badezimmer putzen", "Bad", 30, "FREQ=WEEKLY;BYDAY=SA", monday, Distribution.BALANCED,
                adults);
        recurring(context, "Staubsaugen", "Wohnzimmer", 20, "FREQ=WEEKLY;BYDAY=WE", monday, Distribution.ROTATION,
                everyone);
        interval(context, "Fenster putzen", "Haus", 60, INTERVAL_MONTH, monday, Distribution.OPEN, adults);
        interval(context, "Bettwäsche wechseln", "Schlafzimmer", 25, INTERVAL_FORTNIGHT, monday, Distribution.FIXED,
                List.of(family.partner().getId()));
        chores.create(new ChoreInput("Katzenklo reinigen", "Haus", null, 10, 10, 10, ChoreKind.ON_DEMAND, null,
                monday, null, null, Distribution.OPEN, everyone), context.actor());

        rewards.create("Eine Stunde Tablet-Zeit", "Extra Bildschirmzeit am Wochenende.", 30, context.actor());
        rewards.create("Eis essen gehen", "Ein Eis nach Wahl in der Eisdiele.", 60, context.actor());
        rewards.create("Kinoabend", "Film aussuchen und Popcorn dazu.", 150, context.actor());
    }

    private void recurring(DemoContext context, String title, String area, int minutes, String rrule,
                           LocalDate startsOn, Distribution distribution, List<Long> memberIds) {
        chores.create(new ChoreInput(title, area, null, minutes, minutes, minutes, ChoreKind.RECURRING, rrule,
                startsOn, null, null, distribution, memberIds), context.actor());
    }

    private void interval(DemoContext context, String title, String area, int minutes, int intervalDays,
                          LocalDate startsOn, Distribution distribution, List<Long> memberIds) {
        chores.create(new ChoreInput(title, area, null, minutes, minutes, minutes, ChoreKind.INTERVAL, null,
                startsOn, null, intervalDays, distribution, memberIds), context.actor());
    }
}
