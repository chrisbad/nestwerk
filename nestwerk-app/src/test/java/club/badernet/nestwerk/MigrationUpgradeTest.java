package club.badernet.nestwerk;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates dürfen keine Daten verlieren: Bestandsdaten im Schema einer früheren Version werden auf den neuesten
 * Stand migriert, ohne dass Zeilen verschwinden oder sich ändern.
 */
class MigrationUpgradeTest {

    static final List<String> TABLES = List.of("users", "user_roles", "family_members", "calendar_events",
            "calendar_event_members", "calendar_event_exceptions", "contacts", "todos", "todo_tags", "todo_watchers",
            "todo_assignees", "todo_comments", "todo_history", "todo_completions", "todo_links", "todo_event_links",
            "chores", "chore_members", "chore_tasks", "chore_credits", "rewards", "reward_redemptions",
            "notifications", "backup_runs");

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void start() {
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target)
                .cleanDisabled(false).load();
    }

    @Test
    void upgradesFromV18KeepingAllRows() throws SQLException {
        flyway("latest").clean();
        flyway("18").migrate();
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/upgrade/V18-sample.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Integer> before = counts(jdbc);
        assertThat(before.values()).allMatch(n -> n > 0);

        flyway("latest").migrate();

        assertThat(counts(jdbc)).isEqualTo(before);
        assertThat(flyway("latest").info().current().getVersion().getVersion())
                .isEqualTo(flyway("latest").info().all()[flyway("latest").info().all().length - 1]
                        .getVersion().getVersion());
        assertThat(jdbc.queryForObject("SELECT description FROM calendar_events WHERE id = 3001", String.class))
                .isEqualTo("Mit Umlauten äöü");
        assertThat(jdbc.queryForObject("SELECT rrule FROM calendar_events WHERE id = 3002", String.class))
                .isEqualTo("FREQ=WEEKLY;BYDAY=TU");
        assertThat(jdbc.queryForObject("SELECT title FROM todos WHERE id = 5003 AND parent_id = 5001", String.class))
                .isEqualTo("Regal abbauen");
        assertThat(jdbc.queryForObject("SELECT minutes FROM chore_credits WHERE id = 6201", Integer.class))
                .isEqualTo(25);
    }

    @Test
    void upgradesFreeTextSchoolsAndTeachersToContacts() throws SQLException {
        flyway("latest").clean();
        flyway("29").migrate();
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/upgrade/V29-school-sample.sql"));
        }

        flyway("latest").migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Long> schools = jdbc.queryForList(
                "SELECT id FROM contacts WHERE category = 'Schule' AND name = 'Grundschule am Park'", Long.class);
        assertThat(schools).hasSize(1);
        assertThat(jdbc.queryForList("SELECT school_contact_id FROM school_year ORDER BY id", Long.class))
                .containsExactly(schools.getFirst(), schools.getFirst());
        List<Long> teachers = jdbc.queryForList(
                "SELECT id FROM contacts WHERE category = 'Lehrkraft' AND name = 'Frau Weber'", Long.class);
        assertThat(teachers).hasSize(1);
        assertThat(jdbc.queryForList("SELECT subject_id FROM school_subject_teacher WHERE contact_id = ? "
                + "ORDER BY subject_id", Long.class, teachers.getFirst())).containsExactly(4201L, 4202L);
        assertThat(jdbc.queryForObject("SELECT created_by FROM contacts WHERE id = ?", Long.class,
                schools.getFirst())).isEqualTo(1001L);
        assertThat(jdbc.queryForList("SELECT member_id FROM school_exam_member WHERE exam_id = 4301", Long.class))
                .containsExactly(2001L);
        assertThat(jdbc.queryForObject("SELECT period_from FROM school_lesson WHERE id = 4251", Integer.class))
                .isNull();
    }

    private static Map<String, Integer> counts(JdbcTemplate jdbc) {
        return TABLES.stream().collect(Collectors.toMap(t -> t,
                t -> jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class)));
    }
}
