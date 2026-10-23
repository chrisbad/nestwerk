package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.household.ChoreCredit;
import club.badernet.nestwerk.household.ChoreDate;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreStats;
import club.badernet.nestwerk.household.DurationFormat;
import club.badernet.nestwerk.household.DurationUnit;
import club.badernet.nestwerk.household.HouseholdSettings;
import club.badernet.nestwerk.household.IcalImporter;
import club.badernet.nestwerk.household.Redemption;
import club.badernet.nestwerk.household.Reward;
import club.badernet.nestwerk.household.RewardService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Haushalt: Auswertung, Zeitkonto-Korrekturen, Belohnungen und Kalender-Abos der Hausarbeiten. */
@Component
public class HouseholdAccountTools {

    public record MemberStat(Long memberId, String member, int minutes, String display, int easy, int medium,
                             int hard, int overdue) {
    }

    public record Stats(String from, String to, String unit, String personDay, List<MemberStat> members) {
    }

    public record CreditItem(Long creditId, Long taskId, String chore, LocalDate dueOn, String member, int minutes,
                             int plannedMinutes, boolean corrected, String correctionReason) {
    }

    public record RewardItem(Long rewardId, String title, String description, int costMinutes, boolean active) {
    }

    public record RedemptionItem(Long redemptionId, String reward, String member, int costMinutes, String status,
                                 String requestedAt) {
    }

    public record RewardOverview(Map<String, Integer> balances, List<RewardItem> rewards,
                                 List<RedemptionItem> pending, List<RedemptionItem> recent) {
    }

    public record IcalResult(int added, int moved, int removed, int total) {
    }

    private final Tools tools;
    private final ChoreStats stats;
    private final RewardService rewards;
    private final ChoreService chores;
    private final IcalImporter importer;
    private final FamilyMemberRepository members;
    private final HouseholdSettings settings;
    private final Clock clock;

    public HouseholdAccountTools(Tools tools, ChoreStats stats, RewardService rewards, ChoreService chores,
                                 IcalImporter importer, FamilyMemberRepository members, HouseholdSettings settings,
                                 Clock clock) {
        this.tools = tools;
        this.stats = stats;
        this.rewards = rewards;
        this.chores = chores;
        this.importer = importer;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
    }

    @McpTool(name = "chore_stats", description = "Auswertung des Haushaltsplans: erledigte Minuten und Aufgaben "
            + "je Mitglied und Zeitraum. period: DAY, WEEK, MONTH oder YEAR (Standard WEEK) um das Datum "
            + "(Standard heute). Ohne Auswerte-Recht nur die eigenen Zahlen. minutes ist immer in Minuten, display "
            + "dieselbe Zahl in der gewählten Einheit.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Stats choreStats(McpTransportContext context,
                            @McpToolParam(description = "DAY, WEEK, MONTH oder YEAR", required = false) String period,
                            @McpToolParam(description = "Datum im Zeitraum, JJJJ-MM-TT", required = false)
                            String date,
                            @McpToolParam(description = "Einheit für display: MINUTES, HOURS_MINUTES, HOURS_DECIMAL, "
                                    + "PERSON_DAYS oder AUTO (Standard MINUTES)", required = false) String unit) {
        return tools.read(context, user -> {
            LocalDate day = Tools.date(date, "date");
            Range range = range(period, day == null ? LocalDate.now(clock) : day);
            DurationFormat format = settings.format(durationUnit(unit), range.key());
            return new Stats(range.from().toString(), range.to().toString(), format.unit().name(),
                    format.unit() == DurationUnit.PERSON_DAYS ? format.hint() : null,
                    stats.forPeriod(user, range.from(), range.to()).stream()
                            .map(s -> new MemberStat(s.member().getId(), s.member().getName(), s.minutes(),
                                    format.of(s.minutes()), s.easy(), s.medium(), s.hard(), s.overdue())).toList());
        });
    }

    @McpTool(name = "set_person_day_hours", description = "Stellt ein, wie viele Stunden ein Personentag (PT) hat "
            + "(Standard 8, höchstens 24); die Auswertung rechnet damit. Braucht das Recht, Hausarbeiten anzulegen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String setPersonDayHours(McpTransportContext context,
                                    @McpToolParam(description = "Stunden pro Personentag, z. B. 7.5") Double hours) {
        return tools.write(context, user -> {
            settings.setHoursPerPersonDay(BigDecimal.valueOf(Tools.required(hours, "hours")), user);
            return "1 PT = " + HouseholdSettings.plain(settings.hoursPerPersonDay()) + " h";
        });
    }

    private static DurationUnit durationUnit(String unit) {
        try {
            return DurationUnit.parse(unit);
        } catch (IllegalArgumentException e) {
            throw new Tools.ToolException(e.getMessage());
        }
    }

    @McpTool(name = "list_chore_credits", description = "Listet die Gutschriften (erledigte Hausarbeiten mit "
            + "Minuten) eines Zeitraums, neueste zuerst, optional nur eines Mitglieds. creditId braucht "
            + "correct_chore_credit.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<CreditItem> listChoreCredits(McpTransportContext context,
                                             @McpToolParam(description = "DAY, WEEK, MONTH oder YEAR", required = false)
                                             String period,
                                             @McpToolParam(description = "Datum im Zeitraum, JJJJ-MM-TT",
                                                     required = false) String date,
                                             @McpToolParam(description = "Nur Gutschriften dieses Mitglieds "
                                                     + "(Mitglieds-ID aus list_members)", required = false)
                                             Long memberId) {
        return tools.read(context, user -> {
            LocalDate day = Tools.date(date, "date");
            Range range = range(period, day == null ? LocalDate.now(clock) : day);
            Map<Long, String> names = names();
            return stats.credits(user, range.from(), range.to(), memberId).stream().map(c -> credit(c, names))
                    .toList();
        });
    }

    @McpTool(name = "correct_chore_credit", description = "Korrigiert die Minuten einer Gutschrift (mit Begründung, "
            + "braucht das Recht „Korrigieren“).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String correctChoreCredit(McpTransportContext context,
                                     @McpToolParam(description = "creditId aus list_chore_credits") Long creditId,
                                     @McpToolParam(description = "Korrigierte Minuten") Integer minutes,
                                     @McpToolParam(description = "Grund (höchstens 200 Zeichen)") String reason) {
        return tools.write(context, user -> {
            stats.correct(Tools.required(creditId, "creditId"), Tools.required(minutes, "minutes"), reason, user);
            return "Gutschrift korrigiert.";
        });
    }

    @McpTool(name = "list_rewards", description = "Zeigt Belohnungen, das Minutenkonto je Mitglied, offene "
            + "Einlösungs-Anträge (nur mit Recht zum Bestätigen) und die letzten Einlösungen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public RewardOverview listRewards(McpTransportContext context) {
        return tools.read(context, user -> {
            if (!rewards.can(user, "VIEW")) {
                throw new AccessDeniedException("Belohnungen ansehen");
            }
            Map<Long, String> names = names();
            Map<String, Integer> balances = new java.util.LinkedHashMap<>();
            rewards.balances().forEach((id, minutes) -> balances.put(names.getOrDefault(id, "#" + id) + " (" + id
                    + ")", minutes));
            boolean manage = rewards.can(user, "MANAGE");
            return new RewardOverview(balances,
                    rewards.rewards().stream().filter(r -> r.isActive() || manage).map(HouseholdAccountTools::reward)
                            .toList(),
                    rewards.can(user, "APPROVE") ? rewards.pending().stream().map(r -> redemption(r, names)).toList()
                            : List.of(),
                    rewards.recent(user, 20).stream().map(r -> redemption(r, names)).toList());
        });
    }

    @McpTool(name = "create_reward", description = "Legt eine Belohnung an, die Mitglieder mit Minuten bezahlen "
            + "(braucht das Recht „Verwalten“).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public RewardItem createReward(McpTransportContext context,
                                   @McpToolParam(description = "Titel, z. B. Eis essen") String title,
                                   @McpToolParam(description = "Kosten in Minuten") Integer costMinutes,
                                   @McpToolParam(description = "Beschreibung", required = false) String description) {
        return tools.write(context, user -> reward(rewards.create(title, description, costMinutes, user)));
    }

    @McpTool(name = "update_reward", description = "Ändert eine Belohnung. Nicht angegebene Felder bleiben; "
            + "active=false nimmt sie aus dem Angebot.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public RewardItem updateReward(McpTransportContext context,
                                   @McpToolParam(description = "rewardId aus list_rewards") Long rewardId,
                                   @McpToolParam(description = "Titel", required = false) String title,
                                   @McpToolParam(description = "Kosten in Minuten", required = false)
                                   Integer costMinutes,
                                   @McpToolParam(description = "Beschreibung", required = false) String description,
                                   @McpToolParam(description = "Im Angebot", required = false) Boolean active) {
        return tools.write(context, user -> {
            Reward current = rewards.get(Tools.required(rewardId, "rewardId"));
            rewards.update(rewardId, title != null ? title : current.getTitle(),
                    description != null ? description : current.getDescription(),
                    costMinutes != null ? costMinutes : current.getCostMinutes(),
                    active != null ? active : current.isActive(), user);
            return reward(rewards.get(rewardId));
        });
    }

    @McpTool(name = "redeem_reward", description = "Beantragt eine Belohnung für sich oder (mit Recht dafür) ein "
            + "anderes Mitglied. Die Minuten sind bis zur Entscheidung vorgemerkt.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public RedemptionItem redeemReward(McpTransportContext context,
                                       @McpToolParam(description = "rewardId aus list_rewards") Long rewardId,
                                       @McpToolParam(description = "Mitglieds-ID, sonst das eigene Mitglied",
                                               required = false) Long memberId) {
        return tools.write(context, user -> redemption(
                rewards.request(Tools.required(rewardId, "rewardId"), memberId, user), names()));
    }

    @McpTool(name = "decide_redemption", description = "Bestätigt (approve=true) oder lehnt ab (approve=false) einen "
            + "Einlösungs-Antrag; bei Ablehnung gehen die Minuten zurück aufs Konto.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String decideRedemption(McpTransportContext context,
                                   @McpToolParam(description = "redemptionId aus list_rewards") Long redemptionId,
                                   @McpToolParam(description = "true = bestätigen, false = ablehnen") Boolean approve) {
        return tools.write(context, user -> {
            boolean yes = Tools.required(approve, "approve");
            rewards.decide(Tools.required(redemptionId, "redemptionId"), yes, user);
            return yes ? "Einlösung bestätigt." : "Einlösung abgelehnt.";
        });
    }

    @McpTool(name = "remove_chore_date", description = "Entfernt einen festen Termin einer Hausarbeit (kind DATES); "
            + "die offene Aufgabe dazu entfällt, erledigte bleiben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = true))
    public String removeChoreDate(McpTransportContext context,
                                  @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId,
                                  @McpToolParam(description = "Tag, JJJJ-MM-TT") String day,
                                  @McpToolParam(description = "Uhrzeit HH:MM, falls der Termin eine hat",
                                          required = false) String time) {
        return tools.write(context, user -> {
            Long id = Tools.required(choreId, "choreId");
            LocalDate d = Tools.required(Tools.date(day, "day"), "day");
            LocalTime t = Tools.time(time, "time");
            ChoreDate date = chores.dates(id).stream()
                    .filter(x -> x.getDueOn().equals(d) && java.util.Objects.equals(x.getDueTime(), t))
                    .findFirst().orElseThrow(() -> new Tools.ToolException("Diesen Termin gibt es nicht."));
            chores.removeDate(id, date.getId(), user);
            return "Termin entfernt.";
        });
    }

    @McpTool(name = "subscribe_chore_ical", description = "Abonniert einen iCal-Kalender (URL, z. B. Müllabfuhr) für "
            + "eine Hausarbeit mit festen Terminen und übernimmt die künftigen Termine; danach täglich "
            + "aktualisiert. filter: nur Termine, deren Titel den Text enthält. daysBefore: Aufgabe so viele Tage "
            + "vor dem Termin fällig.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public IcalResult subscribeChoreIcal(McpTransportContext context,
                                         @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId,
                                         @McpToolParam(description = "URL des iCal-Kalenders") String url,
                                         @McpToolParam(description = "Titel-Filter", required = false) String filter,
                                         @McpToolParam(description = "Tage vorher (Standard 0)", required = false)
                                         Integer daysBefore,
                                         @McpToolParam(description = "Fällig um HH:MM", required = false) String time) {
        return tools.write(context, user -> {
            IcalImporter.Result r = importer.subscribe(Tools.required(choreId, "choreId"),
                    Tools.required(url, "url"), filter, daysBefore == null ? 0 : daysBefore,
                    Tools.time(time, "time"), user);
            return new IcalResult(r.added(), r.moved(), r.removed(), r.total());
        });
    }

    @McpTool(name = "unsubscribe_chore_ical", description = "Beendet das iCal-Abo einer Hausarbeit; die "
            + "übernommenen Termine bleiben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String unsubscribeChoreIcal(McpTransportContext context,
                                       @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId) {
        return tools.write(context, user -> {
            importer.unsubscribe(Tools.required(choreId, "choreId"), user);
            return "Abo beendet.";
        });
    }

    private record Range(String key, LocalDate from, LocalDate to) {
    }

    private static Range range(String period, LocalDate date) {
        String key = period == null || period.isBlank() ? "WEEK" : period.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "DAY" -> new Range("day", date, date);
            case "MONTH" -> new Range("month", date.withDayOfMonth(1), date.withDayOfMonth(1).plusMonths(1).minusDays(1));
            case "YEAR" -> new Range("year", date.withDayOfYear(1), date.withDayOfYear(1).plusYears(1).minusDays(1));
            case "WEEK" -> {
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Range("week", monday, monday.plusDays(6));
            }
            default -> throw new Tools.ToolException("period: erlaubt sind DAY, WEEK, MONTH, YEAR.");
        };
    }

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    private static CreditItem credit(ChoreCredit c, Map<Long, String> names) {
        return new CreditItem(c.getId(), c.getTask().getId(), c.getTask().getChore().getTitle(),
                c.getTask().getDueOn(), names.getOrDefault(c.getMemberId(), "#" + c.getMemberId()),
                c.effectiveMinutes(), c.getMinutes(), c.isCorrected(), c.getCorrectionReason());
    }

    private static RewardItem reward(Reward r) {
        return new RewardItem(r.getId(), r.getTitle(), r.getDescription(), r.getCostMinutes(), r.isActive());
    }

    private static RedemptionItem redemption(Redemption r, Map<Long, String> names) {
        return new RedemptionItem(r.getId(), r.getReward().getTitle(),
                names.getOrDefault(r.getMemberId(), "#" + r.getMemberId()), r.getCostMinutes(),
                r.getStatus().name(), String.valueOf(r.getRequestedAt()));
    }

    @McpTool(name = "delete_reward", description = "Löscht eine Belohnung endgültig (braucht das Recht „Verwalten“). Hat jemand sie schon beantragt oder eingelöst, geht das nicht; dann mit update_reward active=false aus dem Angebot nehmen. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteReward(McpTransportContext context,
                           @McpToolParam(description = "ID der Belohnung aus list_rewards") Long rewardId) {
        return tools.write(context, user -> {
            Long id = Tools.required(rewardId, "rewardId");
            String title = rewards.get(id).getTitle();
            rewards.delete(id, user);
            return "Belohnung „" + title + "“ wurde gelöscht.";
        });
    }

    @McpTool(name = "get_reward", description = "Zeigt eine Belohnung mit Preis in Minuten. Deaktivierte sehen nur "
            + "Verwaltende.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public RewardItem getReward(McpTransportContext context,
                                @McpToolParam(description = "ID der Belohnung aus list_rewards") Long rewardId) {
        return tools.read(context, user -> {
            if (!rewards.can(user, "VIEW")) {
                throw new AccessDeniedException("Belohnungen ansehen");
            }
            Reward reward = rewards.get(Tools.required(rewardId, "rewardId"));
            if (!reward.isActive() && !rewards.can(user, "MANAGE")) {
                throw new Tools.ToolException("Belohnung nicht gefunden");
            }
            return reward(reward);
        });
    }
}
