package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolContacts;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared head of all school pages: child chips, school line with office contact, tabs and the settings menu. Also
 * remembers the chosen child in the session so that {@code /school} opens it again.
 */
@Component
public class SchoolHeader {

    static final String SESSION_CHILD = "school.child";

    /** A child with an active school year, as a chip above the tabs. */
    public record Child(Long memberId, String name, String color, Long yearId, boolean selected) {
    }

    /** The school of the shown year with its office. */
    public record Office(Long contactId, String name, String phone, String phoneHref, String email,
                         boolean linkable) {
    }

    private final SchoolService school;
    private final FamilyMemberService members;
    private final SchoolAccess access;
    private final GradeService grades;
    private final SchoolContacts schoolContacts;
    private final ContactService contacts;
    private final CurrentUser currentUser;

    public SchoolHeader(SchoolService school, FamilyMemberService members, SchoolAccess access, GradeService grades,
                        SchoolContacts schoolContacts, ContactService contacts, CurrentUser currentUser) {
        this.school = school;
        this.members = members;
        this.access = access;
        this.grades = grades;
        this.schoolContacts = schoolContacts;
        this.contacts = contacts;
        this.currentUser = currentUser;
    }

    /** Loads the year or answers 404. */
    public SchoolYear year(Long yearId) {
        return school.year(yearId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Adds everything the header fragment needs and returns the child of the year. */
    public FamilyMember prepare(Model model, SchoolYear year, String tab, HttpSession session) {
        User user = currentUser.require();
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        Map<Long, FamilyMember> byId = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        FamilyMember member = Optional.ofNullable(byId.get(profile.getMemberId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        session.setAttribute(SESSION_CHILD, member.getId());
        model.addAttribute("year", year);
        model.addAttribute("member", member);
        model.addAttribute("children", children(byId, member.getId()));
        model.addAttribute("office", office(year, user).orElse(null));
        model.addAttribute("tab", tab);
        model.addAttribute("gradesVisible", grades.mayView(year.getId(), user));
        boolean edit = access.has(user, SchoolPermission.SCHOOL_EDIT);
        model.addAttribute("headerEdit", edit);
        model.addAttribute("headerSettings", access.has(user, SchoolPermission.SCHOOL_SETTINGS));
        model.addAttribute("canEdit", edit);
        return member;
    }

    /**
     * The active year of the child to open: the requested one, the one remembered in the session, the user's own
     * member or the first child by name.
     */
    public Optional<SchoolYear> chooseYear(Long requestedMemberId, HttpSession session) {
        List<Child> children = children(members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity())), null);
        if (children.isEmpty()) {
            return Optional.empty();
        }
        Long remembered = session.getAttribute(SESSION_CHILD) instanceof Long id ? id : null;
        Long own = members.findByUserId(currentUser.require().getId()).map(FamilyMember::getId).orElse(null);
        for (Long wanted : new Long[]{requestedMemberId, remembered, own}) {
            Optional<Child> match = children.stream().filter(c -> c.memberId().equals(wanted)).findFirst();
            if (match.isPresent()) {
                return school.year(match.get().yearId());
            }
        }
        return school.year(children.getFirst().yearId());
    }

    private List<Child> children(Map<Long, FamilyMember> byId, Long selectedMemberId) {
        return school.profiles().stream()
                .filter(p -> byId.containsKey(p.getMemberId()))
                .flatMap(p -> school.activeYear(p.getId()).stream().map(y -> {
                    FamilyMember m = byId.get(p.getMemberId());
                    return new Child(m.getId(), m.getName(), m.getColor(), y.getId(),
                            m.getId().equals(selectedMemberId));
                }))
                .sorted(Comparator.comparing(Child::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Optional<Office> office(SchoolYear year, User user) {
        return schoolContacts.get(year.getSchoolContactId()).map((Contact c) -> new Office(c.getId(), c.getName(),
                c.getPhone(), c.phoneHref(), c.getEmail(), contacts.can(user, "VIEW", c)));
    }
}
