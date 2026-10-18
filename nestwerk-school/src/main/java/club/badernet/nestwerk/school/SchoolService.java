package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Schulprofile der Familienmitglieder und ihre Schuljahre. */
@Service
public class SchoolService {

    private final SchoolProfileRepository profiles;
    private final SchoolYearRepository years;
    private final FamilyMemberRepository members;

    public SchoolService(SchoolProfileRepository profiles, SchoolYearRepository years,
                         FamilyMemberRepository members) {
        this.profiles = profiles;
        this.years = years;
        this.members = members;
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

    /** Legt ein Schuljahr an und macht es zum aktiven; das bisher aktive wird inaktiv, bleibt aber erhalten. */
    @Transactional
    public SchoolYear startYear(Long profileId, String label, String school, String className, LocalDate startsOn,
                                LocalDate endsOn) {
        requireProfile(profileId);
        SchoolYear year = new SchoolYear(profileId, requireText(label, "Bezeichnung", 30),
                requireText(school, "Schule", 150), requireText(className, "Klasse", 30), requireDate(startsOn),
                requireEnd(startsOn, endsOn));
        deactivateCurrent(profileId);
        year.setActive(true);
        return years.save(year);
    }

    @Transactional
    public void changeYear(Long yearId, String label, String school, String className, LocalDate startsOn,
                           LocalDate endsOn) {
        requireYear(yearId).change(requireText(label, "Bezeichnung", 30), requireText(school, "Schule", 150),
                requireText(className, "Klasse", 30), requireDate(startsOn), requireEnd(startsOn, endsOn));
    }

    /** Macht ein früheres Schuljahr wieder zum aktiven. */
    @Transactional
    public void activate(Long yearId) {
        SchoolYear year = requireYear(yearId);
        deactivateCurrent(year.getProfileId());
        year.setActive(true);
    }

    private void deactivateCurrent(Long profileId) {
        years.findByProfileIdAndActiveTrue(profileId).ifPresent(old -> {
            old.setActive(false);
            years.flush();
        });
    }

    private SchoolProfile requireProfile(Long id) {
        return profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("Schulprofil nicht gefunden"));
    }

    private SchoolYear requireYear(Long id) {
        return years.findById(id).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
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
