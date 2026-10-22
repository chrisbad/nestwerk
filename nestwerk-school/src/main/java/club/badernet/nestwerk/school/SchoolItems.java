package club.badernet.nestwerk.school;

import club.badernet.nestwerk.item.CalendarItem;
import club.badernet.nestwerk.item.CalendarItemSource;
import club.badernet.nestwerk.item.ItemType;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * School holidays as all-day calendar items of the children whose state they apply to, for viewers with school
 * access. Exams are already events and homework is a to-do, so they appear through their own types.
 */
@Component
public class SchoolItems implements CalendarItemSource {

    static final String HOLIDAY = "HOLIDAY";

    private final SchoolProfileRepository profiles;
    private final SchoolHolidayRepository holidays;
    private final FamilyMemberRepository members;
    private final SchoolAccess access;

    public SchoolItems(SchoolProfileRepository profiles, SchoolHolidayRepository holidays,
                       FamilyMemberRepository members, SchoolAccess access) {
        this.profiles = profiles;
        this.holidays = holidays;
        this.members = members;
        this.access = access;
    }

    @Override
    public ItemType type() {
        return ItemType.SCHOOL;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CalendarItem> items(LocalDate from, LocalDate to, User viewer) {
        if (!access.has(viewer, SchoolPermission.SCHOOL_VIEW)) {
            return List.of();
        }
        return profiles.findAll().stream().map(SchoolProfile::getState).filter(Objects::nonNull).distinct()
                .flatMap(state -> holidays.findOverlapping(state, from, to).stream())
                .map(this::item).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CalendarItem> find(Long id, User viewer) {
        if (!access.has(viewer, SchoolPermission.SCHOOL_VIEW)) {
            return Optional.empty();
        }
        return holidays.findById(id).map(this::item);
    }

    private CalendarItem item(SchoolHoliday holiday) {
        Set<Long> children = profiles.findAll().stream().filter(p -> holiday.getState().equals(p.getState()))
                .map(SchoolProfile::getMemberId).collect(Collectors.toUnmodifiableSet());
        String color = children.stream().map(id -> members.findById(id).map(FamilyMember::getColor).orElse(null))
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new CalendarItem.School(holiday.getId(), holiday.getName(), holiday.getStartsOn().atStartOfDay(),
                holiday.getEndsOn().plusDays(1).atStartOfDay(), true, children, color, HOLIDAY, null, Set.of(),
                HOLIDAY);
    }
}
