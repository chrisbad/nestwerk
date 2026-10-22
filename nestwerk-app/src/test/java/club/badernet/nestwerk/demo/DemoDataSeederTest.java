package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.settings.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.demo-data.enabled=true"})
class DemoDataSeederTest {

    @Autowired FamilyMemberService members;
    @Autowired AppSettings settings;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsTheDemoFamilyAndMarksTheDataAsSeeded() {
        assertThat(members.findAll()).extracting(FamilyMember::getName).contains("Anna", "Lena", "Max");
        assertThat(settings.get(DemoDataSeeder.MARKER)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"calendar_events", "todos", "contacts", "chores", "pantry_items", "shopping_items",
            "meals", "school_profile", "school_lesson", "school_grade"})
    void fillsEveryFeatureArea(String table) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);

        assertThat(rows).isPositive();
    }
}
