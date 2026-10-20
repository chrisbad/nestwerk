package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** School profiles of family members and their school years. */
@Service
public class SchoolService {

    private final SchoolProfileRepository profiles;
    private final SchoolYearRepository years;
    private final FamilyMemberRepository members;
    private final SchoolBlockerSync sync;
    private final SchoolContacts contacts;
    private final PeriodService periods;

    public SchoolService(SchoolProfileRepository profiles, SchoolYearRepository years,
                         FamilyMemberRepository members, SchoolBlockerSync sync, SchoolContacts contacts,
                         PeriodService periods) {
        this.contacts = contacts;
        this.periods = periods;
        this.profiles = profiles;
        this.years = years;
        this.members = members;
        this.sync = sync;
    }

    @Transactional(readOnly = true)
    public List<SchoolProfile> profiles() {
        return profiles.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<SchoolProfile> profile(Long id) {
        return profiles.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<SchoolProfile> profileOf(Long memberId) {
        return profiles.findByMemberId(memberId);
    }

    @Transactional
    public SchoolProfile createProfile(Long memberId, String state) {
        if (!members.existsById(memberId)) {
            throw new IllegalArgumentException("Mitglied nicht gefunden");
        }
        if (profiles.findByMemberId(memberId).isPresent()) {
            throw new IllegalArgumentException("Für dieses Mitglied gibt es schon ein Schulprofil.");
        }
        return profiles.save(new SchoolProfile(memberId, cleanState(state)));
    }

    @Transactional
    public void changeState(Long profileId, String state) {
        requireProfile(profileId).changeState(cleanState(state));
    }

    @Transactional(readOnly = true)
    public List<SchoolYear> yearsOf(Long profileId) {
        return years.findAllByProfileIdOrderByStartsOnDesc(profileId);
    }

    @Transactional(readOnly = true)
    public Optional<SchoolYear> activeYear(Long profileId) {
        return years.findByProfileIdAndActiveTrue(profileId);
    }

    @Transactional(readOnly = true)
    public Optional<SchoolYear> year(Long id) {
        return years.findById(id);
    }

    /**
     * Starts a school year and makes it the active one; the previously active year stays available.
     * {@code schoolContactId} is the contact of the school and may be {@code null}.
     */
    @Transactional
    public SchoolYear startYear(Long profileId, String label, Long schoolContactId, String className,
                                LocalDate startsOn, LocalDate endsOn) {
        requireProfile(profileId);
        SchoolYear year = new SchoolYear(profileId, requireText(label, "Bezeichnung", 30),
                requireSchool(schoolContactId), requireText(className, "Klasse", 30), requireDate(startsOn),
                requireEnd(startsOn, endsOn));
        Long previous = deactivateCurrent(profileId);
        year.setActive(true);
        SchoolYear saved = years.save(year);
        if (previous != null) {
            sync.sync(previous);
        }
        sync.sync(saved.getId());
        return saved;
    }

    @Transactional
    public void changeYear(Long yearId, String label, Long schoolContactId, String className, LocalDate startsOn,
                           LocalDate endsOn) {
        SchoolYear year = requireYear(yearId);
        Long previousSchool = year.getSchoolContactId();
        year.change(requireText(label, "Bezeichnung", 30), requireSchool(schoolContactId),
                requireText(className, "Klasse", 30), requireDate(startsOn), requireEnd(startsOn, endsOn));
        years.flush();
        if (!java.util.Objects.equals(previousSchool, schoolContactId)) {
            periods.align(yearId);
        }
        sync.sync(yearId);
    }

    /** Makes an earlier school year the active one again. */
    @Transactional
    public void activate(Long yearId) {
        SchoolYear year = requireYear(yearId);
        Long previous = deactivateCurrent(year.getProfileId());
        year.setActive(true);
        years.flush();
        if (previous != null) {
            sync.sync(previous);
        }
        sync.sync(yearId);
    }

    /** Deactivates the active year and returns its id ({@code null} if none was active). */
    private Long deactivateCurrent(Long profileId) {
        return years.findByProfileIdAndActiveTrue(profileId).map(old -> {
            old.setActive(false);
            years.flush();
            return old.getId();
        }).orElse(null);
    }

    private SchoolProfile requireProfile(Long id) {
        return profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("Schulprofil nicht gefunden"));
    }

    private SchoolYear requireYear(Long id) {
        return years.findById(id).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
    }

    private Long requireSchool(Long contactId) {
        if (contactId != null && !contacts.exists(contactId)) {
            throw new IllegalArgumentException("Schule nicht gefunden");
        }
        return contactId;
    }

    private static String cleanState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String code = state.trim().toUpperCase();
        if (!GermanHolidays.STATES.containsKey(code)) {
            throw new IllegalArgumentException("Unbekanntes Bundesland");
        }
        return code;
    }

    private static String requireText(String value, String field, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > max) {
            throw new IllegalArgumentException(field + ": 1–" + max + " Zeichen erforderlich.");
        }
        return clean;
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Beginn und Ende des Schuljahrs sind erforderlich.");
        }
        return date;
    }

    private static LocalDate requireEnd(LocalDate start, LocalDate end) {
        requireDate(start);
        requireDate(end);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Das Schuljahr muss nach seinem Beginn enden.");
        }
        return end;
    }
}
