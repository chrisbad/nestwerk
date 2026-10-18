package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class SchoolServiceTest {

    @Autowired SchoolService school;
    @Autowired FamilyMemberService members;

    FamilyMember lena;
    SchoolProfile profile;

    @BeforeEach
    void setUp() {
        lena = members.create("Lena", "#aabbcc", null);
        profile = school.createProfile(lena.getId(), "by");
    }

    @Test
    void storesStateAsUpperCaseCode() {
        assertThat(profile.getState()).isEqualTo("BY");
        school.changeState(profile.getId(), "nw");
        assertThat(school.profile(profile.getId()).orElseThrow().getState()).isEqualTo("NW");
        school.changeState(profile.getId(), "");
        assertThat(school.profile(profile.getId()).orElseThrow().getState()).isNull();
    }

    @Test
    void rejectsUnknownStateAndSecondProfile() {
        assertThatThrownBy(() -> school.changeState(profile.getId(), "XX"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bundesland");
        assertThatThrownBy(() -> school.createProfile(lena.getId(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schon ein Schulprofil");
        assertThatThrownBy(() -> school.createProfile(999_999L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newYearBecomesActiveAndKeepsTheOldOne() {
        SchoolYear first = school.startYear(profile.getId(), "2025/26", "Grundschule", "3a",
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 7, 31));
        SchoolYear second = school.startYear(profile.getId(), "2026/27", "Grundschule", "4a",
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 7, 31));

        assertThat(school.activeYear(profile.getId()).orElseThrow().getId()).isEqualTo(second.getId());
        assertThat(school.yearsOf(profile.getId())).extracting(SchoolYear::getLabel)
                .containsExactly("2026/27", "2025/26");
        assertThat(school.year(first.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void earlierYearCanBeActivatedAgain() {
        SchoolYear first = school.startYear(profile.getId(), "2025/26", "Grundschule", "3a",
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 7, 31));
        school.startYear(profile.getId(), "2026/27", "Grundschule", "4a",
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 7, 31));

        school.activate(first.getId());

        assertThat(school.activeYear(profile.getId()).orElseThrow().getLabel()).isEqualTo("2025/26");
        assertThat(school.yearsOf(profile.getId()).stream().filter(SchoolYear::isActive)).hasSize(1);
    }

    @Test
    void validatesYearFields() {
        assertThatThrownBy(() -> school.startYear(profile.getId(), " ", "Schule", "3a",
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 7, 31)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bezeichnung");
        assertThatThrownBy(() -> school.startYear(profile.getId(), "x", "Schule", "3a",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 7, 31)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nach seinem Beginn");
        assertThatThrownBy(() -> school.startYear(profile.getId(), "x", "Schule", "3a", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changesYearData() {
        SchoolYear year = school.startYear(profile.getId(), "2025/26", "Grundschule", "3a",
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 7, 31));

        school.changeYear(year.getId(), "2025/26", "Schule am Park", "3b",
                LocalDate.of(2025, 9, 2), LocalDate.of(2026, 7, 30));

        SchoolYear loaded = school.year(year.getId()).orElseThrow();
        assertThat(loaded.getSchool()).isEqualTo("Schule am Park");
        assertThat(loaded.getClassName()).isEqualTo("3b");
        assertThat(loaded.getStartsOn()).isEqualTo(LocalDate.of(2025, 9, 2));
    }
}
